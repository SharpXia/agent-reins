package dev.sharpxia.agentreins.desktopui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
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
 *   容量帽 [DEFAULT_DISK_CAP_BYTES]=256MB 写后惰性触发；
 * - 网络：HttpURLConnection connectTimeout 8s / readTimeout 15s + UA 头（修旧 openStream
 *   无超时无 UA）；同 key 在途 [MediaCacheStore.load] Deferred 合流（LazyColumn 重挂载
 *   风暴同 URL 只发一次请求）；
 * - 键归一（复核 B6 对锁）：[stableCacheKey] 与 iOS MediaDiskCache 同一时效/签名参数闭集
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
     * 取图：内存 → 磁盘（命中 touch mtime 后回填内存）→ 网络（在途合流；成功回填内存 +
     * 异步写盘）。任何一层失败降级 null（调用方落链接 chip）。CancellationException 照抛
     * （调用方作用域取消即放弃等待；底层取数在 [scope] 继续完成并落缓存）。
     */
    suspend fun load(url: String): ImageBitmap? {
        val key = stableCacheKey(url)
        synchronized(memLock) { mem[key] }?.let { return it }
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

    /** 网络 → 解码 → 内存 + 异步写盘（解码失败不落盘——坏字节不占磁盘帽）。 */
    private suspend fun fetchDecodePersist(url: String, key: String): ImageBitmap? {
        val bytes = fetchBytes(url) ?: return null
        val bitmap = decodeForDisplay(bytes) ?: return null
        putMemory(key, bitmap)
        writeScope.launch {
            runCatching {
                dir.mkdirs()
                fileFor(key).writeBytes(bytes)
                trimDiskIfOverCap()
            }
        }
        return bitmap
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

    /** 磁盘惰性 trim：总量超 [capBytes] 才触发，mtime 升序删至回帽内。 */
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

/** 磁盘帽 256MB，写后惰性 trim。 */
internal const val DEFAULT_DISK_CAP_BYTES: Long = 256L * 1024 * 1024

/** 解码降采样帽（与 iOS MediaSupport.decodeForDisplay 2048 同值对锁）。 */
internal const val DEFAULT_MAX_PIXEL: Int = 2048

/** 网络超时（修旧 openStream 无超时：行内图卡死时间线取数协程的风险面）。 */
internal const val MEDIA_CONNECT_TIMEOUT_MS: Int = 8_000
internal const val MEDIA_READ_TIMEOUT_MS: Int = 15_000

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

/** 时效/签名参数白名单（key 小写比对；闭集与 iOS MediaDiskCache 对锁）。 */
private val STRIP_QUERY_PARAMS = setOf(
    "expires", "expire", "expiry", "expires_at", "expiresat",
    "x-amz-date", "x-amz-expires", "x-amz-signature", "x-amz-credential",
    "x-amz-algorithm", "x-amz-signedheaders",
    "signature", "sig", "token", "access_token",
    "oss-expires", "oss-signature", "oss-expires_at",
)

/** 真实网络取数（HttpURLConnection，8s/15s 超时 + UA；非 2xx 与异常一律 null 降级）。 */
internal fun fetchFromNetwork(url: String): ByteArray? = runCatching {
    // URI→URL（旧 openStream 直拉同构：URL(String) 构造器 JDK 层已弃用）
    val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = MEDIA_CONNECT_TIMEOUT_MS
    conn.readTimeout = MEDIA_READ_TIMEOUT_MS
    conn.setRequestProperty("User-Agent", MEDIA_USER_AGENT)
    try {
        if (conn.responseCode !in 200..299) return@runCatching null
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

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
}
