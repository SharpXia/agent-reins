package dev.sharpxia.agentreins.desktopui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * 桌面图片二级缓存（2026-11-23 批次二轮；对齐 iOS MediaDiskCache/HubMediaLoader 语义锚——
 * 桌面此前 MarkdownHttpsImage 每次组合 openStream 直拉原图：LazyColumn 行离屏销毁组合、
 * 再进视口即重下（重复下载风暴），且无超时无 UA 无解码帽）：
 * - 内存：LinkedHashMap(accessOrder) LRU，字节帽 [DEFAULT_MEMORY_CAP_BYTES]=96MB（复核 B5
 *   对锁：64 条全分辨率位图可 OOM；cost=width*height*4 估字节），超帽逐出最旧——淘汰策略抽
 *   纯函数 [evictOrder] 可单测（最近一条恒保留防单条超帽清空整库空转）；
 * - 解码：[MediaCacheStore.decodeForDisplay] 2048px 帽（与 iOS MediaSupport.decodeForDisplay
 *   同名同帽锚）：skia makeFromEncoded 探尺寸，≤帽直接 makeFromImage 成图；超帽 Canvas
 *   drawImageRect 缩放（行内显示帽 MD_IMAGE_MAX_HEIGHT=320dp，全分辨率解码无收益）；
 * - 磁盘：文件名 = sha256hex([stableCacheKey])；命中 touch mtime；trim 按
 *   Files.getLastModifiedTime 升序删（Windows 卷可能关 atime，时间戳口径统一 mtime）；
 *   容量帽 [DEFAULT_DISK_CAP_BYTES]=256MB 写后惰性触发，双门节流（32 次写或净增 16MB 才
 *   全目录扫一次——自检轮 R1-15 对锁 iOS MediaDiskCache 同参数）；写盘走 tmp 完整落盘 +
 *   Files.move 原子替换（自检轮 4 R4-6 对齐 iOS MediaDiskCache.store 的 .atomic 写——旧
 *   writeBytes 直写目标被 kill/崩溃中断留截断坏文件），load 磁盘解码失败顺手删坏文件自愈；
 * - 失败负缓存（自检轮 4 R4-9，iOS RemoteImageLoader 同轮同参互锚——基准 TTL
 *   [MEDIA_FAILURE_TTL_MS] / 退避帽 [MEDIA_FAILURE_BACKOFF_CAP_MS] 单端改值双端同步）：
 *   键 →（上次失败时刻, 连续失败数）内存级记录，退避窗 = 基准 TTL × 连续失败数（帽封顶）；
 *   窗内取数直回 null 不查盘不启网络——死链外链图行滚动往返不再重发注定失败的请求（8s
 *   连接超时量级的白付 IO）；TTL 到期自动重开窗口、连续失败数驱动退避生长，成功取数即清。
 *   知情差异：桌面失败态降级链接 chip 无「点击重试图片」入口，iOS force 显式重试通道
 *   （MediaSupport.swift RemoteImageLoader）桌面无对位；
 * - 网络：HttpURLConnection connectTimeout 8s / readTimeout 15s + UA 头（修旧 openStream
 *   无超时无 UA）+ 响应体体量帽 32MB（自检轮 2 R2-11：逐块限界读 [readBounded]，超帽 null
 *   降级链接 chip——下载阶段内存帽，解码帽只管已读字节）；同 key 在途 [MediaCacheStore.load]
 *   Deferred 合流（LazyColumn 重挂载风暴同 URL 只发一次请求）；
 * - 键归一（复核 B6 对锁；2026-11-23 自检轮 R1-2/R1-10/R1-20 取并集对齐 iOS，见
 *   [STRIP_QUERY_PARAMS] 注）：[stableCacheKey] 与 iOS MediaDiskCache 同一时效/签名参数闭集
 *   白名单剥离 + query 重排序——同 URL 带刷新签名参数命中同一文件；白名单外参数全保留
 *   （未知签名参数退化为不命中而非错命中，单端增删白名单必须双端同步）；
 * - 写盘走自带 SupervisorJob+Dispatchers.IO 独立作用域：调用方（LaunchedEffect）随行离屏
 *   取消不中断写盘，下次进视口即磁盘命中（勘察风险 4 对锁）。
 */
internal class MediaCacheStore(
    private val dir: File,
    private val memoryCapBytes: Long = DEFAULT_MEMORY_CAP_BYTES,
    private val capBytes: Long = DEFAULT_DISK_CAP_BYTES,
    private val scope: CoroutineScope,
    /** 失败记账时钟注入（自检轮 4 R4-9；默认系统毫秒钟，单测假钟驱动退避窗不真等 TTL）。 */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** 取数通道注入（默认真实网络；单测桩计数在途合流/失败回落）。 */
    private val fetchBytes: suspend (String) -> ByteArray? = ::fetchFromNetwork,
) {
    private val mem = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private val memLock = Any()
    private val diskLock = Any()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** 写盘独立作用域：与调用方（LaunchedEffect/行组合）生命周期解耦。 */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 失败负缓存（自检轮 4 R4-9，iOS RemoteImageLoader.image(for:) 同位同参互锚——基准 TTL
     * × 连续失败数、帽 [MEDIA_FAILURE_BACKOFF_CAP_MS]）：键 →（上次失败时刻, 连续失败数），
     * 取数/解码失败记账续增、成功即清。仅进程内存级（重启即清），退避窗语义见
     * [failureGateOpen]。
     */
    private class FailureRecord(val atMillis: Long, val consecutive: Int)

    private val failures = java.util.concurrent.ConcurrentHashMap<String, FailureRecord>()

    /**
     * 失败门是否放行（R4-9）：无记录放行；有记录未过退避窗（基准 TTL × 连续失败数，帽
     * [MEDIA_FAILURE_BACKOFF_CAP_MS]）拦截——生长来自 TTL 到期重开的自动重试再失败。
     */
    private fun failureGateOpen(key: String): Boolean {
        val record = failures[key] ?: return true
        val windowMs = minOf(MEDIA_FAILURE_TTL_MS * record.consecutive, MEDIA_FAILURE_BACKOFF_CAP_MS)
        return nowMillis() - record.atMillis >= windowMs
    }

    private fun recordFailure(key: String) {
        val prior = failures[key]?.consecutive ?: 0
        failures[key] = FailureRecord(atMillis = nowMillis(), consecutive = prior + 1)
    }

    /**
     * 取图：内存 → 失败负缓存门 → 磁盘（命中 touch mtime 后回填内存；解码失败删坏文件
     * 自愈）→ 网络（在途合流；成功回填内存 + 异步写盘）。任何一层失败降级 null（调用方落
     * 链接 chip）。CancellationException 照抛（调用方作用域取消即放弃等待；底层取数在
     * [scope] 继续完成并落缓存）。
     */
    suspend fun load(url: String): ImageBitmap? {
        val key = stableCacheKey(url)
        synchronized(memLock) { mem[key] }?.let { return it }
        // 失败负缓存门（R4-9）：窗内直回 null——不查盘不启网络，死链行物化即现失败态
        // （chip 不再「加载中…→加载失败」闪跳）。桌面无 force 显式重试通道，TTL 到期自动
        // 重开即重试（iOS force 清记录强拉为 iOS 侧独有，知情差异见类注）。
        if (!failureGateOpen(key)) return null
        val file = fileFor(key)
        if (file.isFile) {
            val diskBitmap = withContext(Dispatchers.IO) {
                runCatching { decodeForDisplay(file.readBytes()) }.getOrNull()
            }
            if (diskBitmap != null) {
                runCatching { file.setLastModified(System.currentTimeMillis()) } // mtime LRU touch
                putMemory(key, diskBitmap)
                return diskBitmap
            }
            // 磁盘自愈（自检轮 4 R4-6）：坏字节文件（历史非原子写中断残留等）解码失败即删
            // ——不删则原 URL 失效（签名过期 refetch 恒 4xx）时坏条目永久驻留，每次进视口
            // 白付 readBytes+解码；删除即让出磁盘帽，下次取数网络重下。
            runCatching { file.delete() }
        }
        val deferred = inflight.computeIfAbsent(key) {
            scope.async { fetchDecodePersist(url, key) }
        }
        deferred.invokeOnCompletion { inflight.remove(key, deferred) }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 网络 → 解码 → 内存 + 异步写盘（解码失败不落盘——坏字节不占磁盘帽）；取数/解码失败
     * 记账驱动失败负缓存退避（R4-9，见 [failures] 注），成功清记录。
     */
    private suspend fun fetchDecodePersist(url: String, key: String): ImageBitmap? {
        val bytes = fetchBytes(url)
        if (bytes == null) {
            recordFailure(key)
            return null
        }
        val bitmap = decodeForDisplay(bytes)
        if (bitmap == null) {
            recordFailure(key) // 坏字节解码失败与取数失败同记（错误方向安全：不显示不落盘）
            return null
        }
        failures.remove(key) // 成功清记录（下次失败从基准 TTL 重新起算）
        putMemory(key, bitmap)
        writeScope.launch {
            runCatching {
                dir.mkdirs()
                atomicWrite(fileFor(key), bytes)
                trimDiskIfOverCapThrottled(bytes.size.toLong())
            }
        }
        return bitmap
    }

    /**
     * 登出清空（确认评审收口，对齐 iOS MediaDiskCache.clearAll 同轮补齐）：媒体字节是
     * 账号派生数据，撤销设备换账号不残留呈现——磁盘此前跨登出持久存活，是双端账号派生
     * 缓存里唯一漏网。内存 mem/failures 同批清空；整目录删除后重建（writeScope 承 IO，
     * 调用方即回）。在途写竞态窗至多残留登出前已在途的单张图（设备已撤销，后续取数必
     * 失败不再增长——知情微窗）。调用方 SettingsScreen 登出链。
     */
    fun clearAll() {
        synchronized(memLock) { mem.clear() }
        failures.clear()
        writeScope.launch {
            runCatching {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        }
    }

    /**
     * 原子落盘（自检轮 4 R4-6，对齐 iOS MediaDiskCache.store 的 .atomic 写）：tmp 完整
     * 落盘后 move 到目标——直写目标文件（旧 writeBytes）被 kill/崩溃中断留截断坏文件，load
     * 解码失败自愈删除（R4-6 前不清）之前坏条目每次进视口白付 IO+解码。中断最坏留 tmp
     * 残片（.tmp 不在 sha256hex 命名空间、不进命中路径，trim 全扫按容量回收）或旧目标完整
     * 文件。REPLACE_EXISTING + ATOMIC_MOVE 双旗标（JDK 21/macOS rename 实证原子替换既有
     * 目标）；ATOMIC_MOVE 不被卷支持（跨文件系统等）退普通替换——tmp 已完整落盘，非原子
     * 窗口收窄到替换一瞬。
     */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile(target.nameWithoutExtension, ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // move 失败残留清理（成功后不存在，delete 幂等）
        }
    }

    fun putMemory(key: String, bitmap: ImageBitmap) {
        synchronized(memLock) {
            mem[key] = bitmap
            // accessOrder LinkedHashMap 迭代序=最旧在前 → 翻转成「最新在前」喂 evictOrder
            val entries = mem.entries.reversed().map {
                it.key to bitmapCost(it.value)
            }
            evictOrder(entries, memoryCapBytes).forEach { mem.remove(it) }
        }
    }

    /**
     * 解码降采样（与 iOS decodeForDisplay maxPixel=2048 同帽同锚）：坏字节/非图片/探尺寸
     * 失败一律 null（调用方降级）；超帽图按比例缩到最长边 = maxPixel。
     */
    fun decodeForDisplay(bytes: ByteArray, maxPixel: Int = DEFAULT_MAX_PIXEL): ImageBitmap? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return@runCatching null
        if (width <= maxPixel && height <= maxPixel) {
            return@runCatching Bitmap.makeFromImage(image).asComposeImageBitmap()
        }
        val scale = maxPixel.toFloat() / maxOf(width, height)
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap()
        bitmap.setImageInfo(ImageInfo.makeN32Premul(scaledWidth, scaledHeight))
        bitmap.allocPixels()
        Canvas(bitmap).drawImageRect(
            image,
            Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()),
            Rect.makeXYWH(0f, 0f, scaledWidth.toFloat(), scaledHeight.toFloat()),
            SamplingMode.MITCHELL, // 降采样质量（缩图 MITCHELL 与 iOS 解码观感同量级）
            Paint(),
            true,
        )
        bitmap.asComposeImageBitmap()
    }.getOrNull()

    /** trim 双门计数器（写盘路径专用；diskLock 内读写——writeScope 是 IO 多线程）。 */
    private var writesSinceTrimScan = 0
    private var bytesAddedSinceTrimScan = 0L

    /**
     * 写盘后 trim 触发（双门节流，自检轮 R1-15 对锁 iOS MediaDiskCache.trimScanWriteInterval/
     * trimScanBytesDelta——距上次全扫累计 ≥[TRIM_SCAN_WRITE_INTERVAL] 次写或净增
     * ≥[TRIM_SCAN_BYTES_DELTA] 才真正全目录扫一次）：帽内大量小缩略图时写路径不再每写付
     * O(n) 全目录枚举+求和+排序代价。超帽清理至多延迟到下一门开（临时超帽 ≤16MB 量级，LRU
     * 语义不变）；重写同键按净增上界计（文件替换不做差量账），触发略偏保守无害。
     * 双端参数须一致（iOS 同轮对齐）。
     */
    private fun trimDiskIfOverCapThrottled(addedBytes: Long) {
        synchronized(diskLock) {
            writesSinceTrimScan += 1
            bytesAddedSinceTrimScan += addedBytes
            if (writesSinceTrimScan < TRIM_SCAN_WRITE_INTERVAL &&
                bytesAddedSinceTrimScan < TRIM_SCAN_BYTES_DELTA
            ) {
                return
            }
            writesSinceTrimScan = 0
            bytesAddedSinceTrimScan = 0
            trimDiskIfOverCap() // diskLock 可重入，直进全扫
        }
    }

    /** 磁盘惰性 trim：总量超 [capBytes] 才触发，mtime 升序删至回帽内（写盘路径经 [trimDiskIfOverCapThrottled] 节流）。 */
    fun trimDiskIfOverCap() {
        synchronized(diskLock) {
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= capBytes) return
            val ordered = files.sortedBy { f ->
                runCatching { Files.getLastModifiedTime(f.toPath()).toMillis() }
                    .getOrElse { f.lastModified() }
            }
            for (f in ordered) {
                if (total <= capBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }

    private fun fileFor(key: String): File = File(dir, sha256Hex(key) + ".img")
}

/** 内存帽 96MB（复核 B5 对锁：一张 12MP RGBA≈48MB，全分辨率位图池可达 GB 级）。 */
internal const val DEFAULT_MEMORY_CAP_BYTES: Long = 96L * 1024 * 1024

/** 磁盘帽 256MB，写后惰性 trim（双门节流，见 MediaCacheStore.trimDiskIfOverCapThrottled 注）。 */
internal const val DEFAULT_DISK_CAP_BYTES: Long = 256L * 1024 * 1024

/** trim 扫描节流双门（自检轮 R1-15，与 iOS MediaDiskCache.trimScanWriteInterval 同值对锁：双端参数须一致）。 */
internal const val TRIM_SCAN_WRITE_INTERVAL = 32

/** trim 扫描节流净增门（与 iOS MediaDiskCache.trimScanBytesDelta 16MB 同值对锁）。 */
internal const val TRIM_SCAN_BYTES_DELTA: Long = 16L * 1024 * 1024

/** 解码降采样帽（与 iOS MediaSupport.decodeForDisplay 2048 同值对锁）。 */
internal const val DEFAULT_MAX_PIXEL: Int = 2048

/**
 * 失败负缓存基准 TTL（自检轮 4 R4-9，与 iOS RemoteImageLoader.failureTTLSeconds=60s 同值
 * 对锁——退避窗 = 本值 × 连续失败数，单端改值双端同步）。
 */
internal const val MEDIA_FAILURE_TTL_MS: Long = 60_000

/** 失败退避窗帽（与 iOS RemoteImageLoader.failureBackoffCapSeconds=300s 同值对锁）。 */
internal const val MEDIA_FAILURE_BACKOFF_CAP_MS: Long = 300_000

/** 网络超时（修旧 openStream 无超时：行内图卡死时间线取数协程的风险面）。 */
internal const val MEDIA_CONNECT_TIMEOUT_MS: Int = 8_000
internal const val MEDIA_READ_TIMEOUT_MS: Int = 15_000

/**
 * 响应体体量帽（自检轮 2 R2-11；与 iOS MediaSupport 下载帽同值对锁——单端改值双端同步）：
 * 2xx 响应体一律限界读，超帽截断返回 null 走链接 chip 降级——错误方向安全（不显示而非
 * OOM），行内图加载协程内存不被超大/恶意外链拉爆；解码帽（decodeForDisplay 2048px）只
 * 作用于已读入字节，下载阶段由本帽把门。
 */
internal const val MEDIA_DOWNLOAD_CAP_BYTES: Long = 32L * 1024 * 1024

/** 限界读拷贝块长（64KB，逐块累计——不依赖 Content-Length，chunked/缺失头同防）。 */
private const val COPY_BUF_BYTES: Int = 64 * 1024

/** 取数 UA 头（不带版本号，随主题/协议无关的稳定标识）。 */
internal const val MEDIA_USER_AGENT = "AgentReins-Desktop"

/**
 * 字节帽淘汰纯函数（可单测）：[entries] = (键, cost) 按「最新在前」排序；返回需逐出的键
 * （从最旧端逐个累计至回帽内）。最近一条恒保留——单条 cost 超帽时不清空整库（防大图反复
 * 逐出-载入空转），宁超帽保留该条。
 */
internal fun evictOrder(entries: List<Pair<String, Long>>, capBytes: Long): List<String> {
    var total = entries.sumOf { it.second }
    if (total <= capBytes) return emptyList()
    val evicted = mutableListOf<String>()
    for (i in entries.indices.reversed()) {
        if (total <= capBytes || i == 0) break
        total -= entries[i].second
        evicted += entries[i].first
    }
    return evicted
}

/**
 * 缓存键归一（复核 B6 对锁；与 iOS MediaDiskCache 同一白名单闭集——单端增删必须双端同步）：
 * 剥离时效/签名参数（case-insensitive）+ 剩余 query 重排序（同资源不同参数序命中同一文件）；
 * fragment 保留（签名 URL 罕见带，原样不影响）。白名单外参数全保留——未知签名参数退化为
 * 不命中（键不同）而非错命中。
 */
internal fun stableCacheKey(raw: String): String {
    val hashIndex = raw.indexOf('#')
    val (noFragment, fragment) = if (hashIndex >= 0) {
        raw.substring(0, hashIndex) to raw.substring(hashIndex)
    } else {
        raw to ""
    }
    val queryIndex = noFragment.indexOf('?')
    if (queryIndex == -1) return raw
    val base = noFragment.substring(0, queryIndex)
    val params = noFragment.substring(queryIndex + 1)
        .split('&')
        .filter { it.isNotEmpty() }
        .filterNot { it.substringBefore('=').lowercase() in STRIP_QUERY_PARAMS }
        .sortedWith(compareBy({ it.substringBefore('=').lowercase() }, { it }))
    return if (params.isEmpty()) base + fragment else base + "?" + params.joinToString("&") + fragment
}

/**
 * 时效/签名参数白名单（key 小写比对；闭集与 iOS MediaDiskCache.volatileQueryKeys 对锁——
 * 自检轮 R1-2/R1-10/R1-20 起取并集对齐：本侧补 x-amz-security-token/oss-access-key-id/
 * oss-security-token，iOS 侧补 oss-expires_at。单端增删必须双端同步改，否则同 URL 两端
 * 归一键漂移；测试 fixture 与 iOS 侧同清单互锚，见 MediaCacheTest）。
 */
private val STRIP_QUERY_PARAMS = setOf(
    "expires", "expire", "expiry", "expires_at", "expiresat",
    "x-amz-date", "x-amz-expires", "x-amz-signature", "x-amz-credential",
    "x-amz-algorithm", "x-amz-signedheaders", "x-amz-security-token",
    "signature", "sig", "token", "access_token",
    "oss-expires", "oss-signature", "oss-expires_at", "oss-access-key-id", "oss-security-token",
)

/** 真实网络取数（HttpURLConnection，8s/15s 超时 + UA + 响应体体量帽；非 2xx/超帽/异常一律 null 降级）。 */
internal fun fetchFromNetwork(url: String): ByteArray? = runCatching {
    // URI→URL（旧 openStream 直拉同构：URL(String) 构造器 JDK 层已弃用）
    val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = MEDIA_CONNECT_TIMEOUT_MS
    conn.readTimeout = MEDIA_READ_TIMEOUT_MS
    conn.setRequestProperty("User-Agent", MEDIA_USER_AGENT)
    try {
        if (conn.responseCode !in 200..299) return@runCatching null
        // 自检轮 2 R2-11：2xx 体量帽——逐块限界读（原 readBytes() 无上限，读到 EOF 为止，
        // 超大响应把取数协程内存拉爆）；超帽 null → 调用方降级链接 chip，不显示不落盘
        conn.inputStream.use { readBounded(it, MEDIA_DOWNLOAD_CAP_BYTES) }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

/**
 * 限界读流（自检轮 2 R2-11，纯函数可单测）：逐块拷贝累计超 [capBytes] 即返回 null（丢弃
 * 已读——宁降级不 OOM）；与 iOS 同轮下载帽同值对锁（[MEDIA_DOWNLOAD_CAP_BYTES] 注）。
 */
internal fun readBounded(input: java.io.InputStream, capBytes: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream(COPY_BUF_BYTES)
    val buf = ByteArray(COPY_BUF_BYTES)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > capBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun bitmapCost(bitmap: ImageBitmap): Long =
    bitmap.width.toLong() * bitmap.height * 4L

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * 生产单例薄封装（目录 ~/.agentreins/cache/media——HubEnv.kt:12 同面根目录；scope 自持
 * SupervisorJob+IO）。lazy 起建：单测注入临时目录直用 [MediaCacheStore]，不触真实目录。
 */
internal object MediaCache {
    private val store: MediaCacheStore by lazy {
        MediaCacheStore(
            dir = File(System.getProperty("user.home"), ".agentreins/cache/media"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    suspend fun load(url: String): ImageBitmap? = store.load(url)

    /** 登出清空（见 [MediaCacheStore.clearAll]）；调用方 SettingsScreen 登出链。 */
    fun clearAll() = store.clearAll()
}
