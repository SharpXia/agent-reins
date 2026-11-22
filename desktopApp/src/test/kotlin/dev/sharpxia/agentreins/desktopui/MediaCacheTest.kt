package dev.sharpxia.agentreins.desktopui

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 批次二图片二级缓存单测（2026-11-23，desktopApp jvmTest，临时目录注入）：键归一白名单、
 * 字节帽淘汰、解码降采样 2048 帽（skia 解码链，样张经 ImageIO 构造）、在途合流单次计数、
 * 磁盘往返 + mtime trim、取数失败回落 null。生产单例 MediaCache（~/.agentreins/cache/media）
 * 不在本测面——单测只注入 MediaCacheStore。
 */
class MediaCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("media-cache-test").toFile()

    /** 2×2 PNG 小样张（纯 JDK 生成，不依赖 skia 造图）。 */
    private fun tinyPngBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0xFF0000)
        image.setRGB(1, 1, 0x00FF00)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** 4000×100 宽幅 PNG（触发 2048 降采样帽）。 */
    private fun widePngBytes(): ByteArray {
        val image = BufferedImage(4000, 100, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    // ── stableCacheKey：键归一 ──

    @Test
    fun stableCacheKeyStripsSignedParamsAndSortsQuery() {
        // 时效/签名参数剥离：同资源带刷新签名 → 同键（缓存命中，不重复下载）
        assertEquals(
            stableCacheKey("https://e.com/x.png?Expires=123&X-Amz-Signature=abc"),
            stableCacheKey("https://e.com/x.png?Expires=999&X-Amz-Signature=zzz"),
        )
        assertEquals("https://e.com/x.png", stableCacheKey("https://e.com/x.png?Expires=123"))
        // query 重排序：参数序不同 → 同键
        assertEquals(
            stableCacheKey("https://e.com/x.png?a=1&b=2"),
            stableCacheKey("https://e.com/x.png?b=2&a=1"),
        )
        // 白名单外参数全保留（未知签名参数退化为不命中而非错命中）
        assertEquals("https://e.com/x.png?v=2", stableCacheKey("https://e.com/x.png?v=2"))
        assertFalse(
            stableCacheKey("https://e.com/x.png?v=2") == stableCacheKey("https://e.com/x.png?v=3"),
        )
        // 大小写不敏感剥离（AWS X-Amz-* 与 OSS oss-* 闭集）
        assertEquals("https://e.com/x.png", stableCacheKey("https://e.com/x.png?oss-expires=1&oss-signature=s"))
        // 双端对锁白名单 fixture（自检轮 R1-2/R1-10/R1-20 并集对齐 iOS MediaDiskCache.
        // volatileQueryKeys——STS 轮转/security-token 与 OSS access-key-id/security-token 家族
        // 剥离；与 iOS 侧测试同一清单互锚，单端增删白名单必须双端同步改）
        assertEquals(
            "https://e.com/x.png",
            stableCacheKey(
                "https://e.com/x.png?x-amz-security-token=t&oss-access-key-id=k" +
                    "&oss-security-token=s&oss-expires_at=9",
            ),
        )
        // 无 query / fragment 原样
        assertEquals("https://e.com/x.png", stableCacheKey("https://e.com/x.png"))
        // 稳定性：同输入两次恒等
        assertEquals(
            stableCacheKey("https://e.com/x.png?a=1"),
            stableCacheKey("https://e.com/x.png?a=1"),
        )
    }

    // ── evictOrder：字节帽淘汰 ──

    @Test
    fun evictOrderEvictsOldestUntilUnderCap() {
        // entries「最新在前」：a(最新,10) b(20) c(最旧,30)
        val entries = listOf("a" to 10L, "b" to 20L, "c" to 30L)
        // 总 60：帽 40 → 逐出最旧 c（剩 30 ≤ 40）
        assertEquals(listOf("c"), evictOrder(entries, capBytes = 40L))
        // 帽 25 → 逐出 c、b（剩 10）
        assertEquals(listOf("c", "b"), evictOrder(entries, capBytes = 25L))
        // 帽充裕 → 不逐出
        assertEquals(emptyList(), evictOrder(entries, capBytes = 60L))
        // 单条超帽：最近一条恒保留（不清空整库，防大图反复逐出-载入空转）
        assertEquals(listOf("c", "b"), evictOrder(entries, capBytes = 5L))
        assertEquals(emptyList(), evictOrder(listOf("only" to 1000L), capBytes = 5L))
    }

    // ── decodeForDisplay：降采样 2048 帽 ──

    @Test
    fun decodeForDisplayDownsamplesBeyond2048Cap() {
        val store = MediaCacheStore(dir = tempDir(), scope = CoroutineScope(Dispatchers.IO))
        // 4000×100 → scale=2048/4000=0.512 → 2048×51
        val bitmap = assertNotNull(store.decodeForDisplay(widePngBytes()))
        assertEquals(2048, bitmap.width)
        assertEquals(51, bitmap.height)
        // 帽内图原尺寸直出
        val tiny = assertNotNull(store.decodeForDisplay(tinyPngBytes()))
        assertEquals(2, tiny.width)
        assertEquals(2, tiny.height)
        // 坏字节/非图片 → null（调用方降级链接 chip）
        assertNull(store.decodeForDisplay(byteArrayOf(0x00, 0x01, 0x02)))
    }

    // ── load：在途合流 / 磁盘往返 / 失败回落 ──

    @Test
    fun inflightMergesConcurrentLoadsToSingleFetch(): Unit = runBlocking {
        val dir = tempDir()
        val fetchCount = AtomicInteger(0)
        val store = MediaCacheStore(
            dir = dir,
            scope = CoroutineScope(Dispatchers.IO),
        ) { url ->
            fetchCount.incrementAndGet()
            delay(50) // 拉长取数窗，让第二个并发 load 命中在途 Deferred
            tinyPngBytes()
        }
        val url = "https://e.com/inflight.png"
        val results = List(4) { async { store.load(url) } }.map { it.await() }
        // 同 key 合流：只取数一次，四路同图
        assertEquals(1, fetchCount.get())
        assertTrue(results.all { it != null })
        // 磁盘往返：文件按 sha256(stableCacheKey) 命名落盘（写盘独立作用域异步完成；R4-6
        // 原子写 tmp→move 有瞬时 .tmp 文件，断言只认落定的 .img 产物）
        withTimeout(5_000) {
            while (dir.listFiles()!!.none { it.name.endsWith(".img") }) delay(10)
        }
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".img") })
        // 文件名 = sha256hex(stableCacheKey(url))（64 位 hex）
        assertEquals(64, dir.listFiles()!!.single { it.name.endsWith(".img") }.nameWithoutExtension.length)
    }

    @Test
    fun memoryHitSkipsRefetch(): Unit = runBlocking {
        val fetchCount = AtomicInteger(0)
        val store = MediaCacheStore(
            dir = tempDir(),
            scope = CoroutineScope(Dispatchers.IO),
        ) {
            fetchCount.incrementAndGet()
            tinyPngBytes()
        }
        val url = "https://e.com/mem.png"
        assertNotNull(store.load(url))
        assertNotNull(store.load(url))
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun fetchFailureFallsBackToNull(): Unit = runBlocking {
        val store = MediaCacheStore(
            dir = tempDir(),
            scope = CoroutineScope(Dispatchers.IO),
        ) { null } // 网络/解码失败 → null（调用方降级链接 chip）
        assertNull(store.load("https://e.com/missing.png"))
        // 取数成功但字节不可解码 → 同样 null 且不落盘
        val decodeFail = MediaCacheStore(
            dir = tempDir(),
            scope = CoroutineScope(Dispatchers.IO),
        ) { byteArrayOf(0x00) }
        assertNull(decodeFail.load("https://e.com/broken.png"))
        decodeFail.trimDiskIfOverCap() // 空目录 no-op（trim 路径可调不抛）
    }

    @Test
    fun callerCancellationDoesNotKillWrite(): Unit = runBlocking {
        // 调用方 await 被取消：底层取数/写盘在 store 自有 scope 继续（LaunchedEffect 生命周期
        // 解耦语义）。runBlocking 子协程取消后，主协程轮询等待文件落盘。
        val dir = tempDir()
        val store = MediaCacheStore(
            dir = dir,
            scope = CoroutineScope(Dispatchers.IO),
        ) {
            delay(50)
            tinyPngBytes()
        }
        val job = launch { store.load("https://e.com/cancel.png") }
        delay(10) // 进入取数窗
        job.cancel(CancellationException("caller left composition"))
        withTimeout(5_000) {
            while (dir.listFiles().isNullOrEmpty()) delay(10)
        }
        assertEquals(1, dir.listFiles()!!.size)
    }

    // ── readBounded：响应体体量帽（自检轮 2 R2-11） ──

    @Test
    fun readBoundedCapsOversizedBodyToNull() {
        // 帽内直通；恰好等于帽 → 收；超帽 → null（逐块累计，不依赖 Content-Length）
        assertEquals("ok", readBounded("ok".byteInputStream(), capBytes = 32)!!.decodeToString())
        assertEquals(8, readBounded(ByteArray(8) { 1 }.inputStream(), 8)!!.size)
        assertNull(readBounded(ByteArray(9) { 1 }.inputStream(), 8))
        // 一次读尽也照判：累计 12 > 帽 8 → null（宁降级 chip 不 OOM）
        assertNull(readBounded(ByteArray(12) { 2 }.inputStream(), 8))
        // 空流 → 空字节数组
        assertEquals(0, readBounded(ByteArray(0).inputStream(), 8)!!.size)
    }

    // ── trim：mtime LRU ──

    @Test
    fun trimDeletesOldestByMtimeUntilUnderCap(): Unit = runBlocking {
        val dir = tempDir()
        val store = MediaCacheStore(dir = dir, capBytes = 150L, scope = CoroutineScope(Dispatchers.IO))
        val a = File(dir, "a.img").apply { writeBytes(ByteArray(60)); setLastModified(1_000L) }
        val b = File(dir, "b.img").apply { writeBytes(ByteArray(60)); setLastModified(2_000L) }
        val c = File(dir, "c.img").apply { writeBytes(ByteArray(60)); setLastModified(3_000L) }
        // 总 180 > 150：mtime 升序删 a（-60）→ 120 ≤ 150 停
        store.trimDiskIfOverCap()
        assertFalse(a.exists())
        assertTrue(b.exists() && c.exists())
    }

    // ── trimDiskIfOverCapThrottled：双门节流（自检轮 3 R3-7） ──

    @Test
    fun trimThrottleDefersFullScanUntilWriteCountGateOpens(): Unit = runBlocking {
        val dir = tempDir()
        // 预置超帽目录（帽 1MB、stale 1.1MB）：门未开写路径零全扫——stale 与全部新文件共存
        val stale = File(dir, "stale.img").apply { writeBytes(ByteArray(1_100_000)); setLastModified(1_000L) }
        val store = MediaCacheStore(dir = dir, capBytes = 1_000_000L, scope = CoroutineScope(Dispatchers.IO)) {
            tinyPngBytes() // 小样张：写数门（32 次）开前字节门（16MB）远不可及
        }
        // 前 31 次写（< TRIM_SCAN_WRITE_INTERVAL）：目录原样，无任何删除
        repeat(31) { i ->
            assertNotNull(store.load("https://e.com/throttle-$i.png"))
            withTimeout(5_000) { while (dir.listFiles()!!.size < i + 2) delay(10) } // 等异步写盘落定（+stale）
            assertTrue(stale.exists())
        }
        assertEquals(32, dir.listFiles()!!.size)
        // 第 32 次写：写数门开 → 全扫 → 超帽删 mtime 最旧的 stale，32 个新文件全保
        assertNotNull(store.load("https://e.com/throttle-31.png"))
        withTimeout(5_000) { while (stale.exists()) delay(10) }
        assertFalse(stale.exists())
        assertEquals(32, dir.listFiles()!!.size)
    }

    @Test
    fun trimThrottleBytesGateOpensOnSingleOversizedWrite(): Unit = runBlocking {
        val dir = tempDir()
        val stale = File(dir, "stale.img").apply { writeBytes(ByteArray(1_100_000)); setLastModified(1_000L) }
        val store = MediaCacheStore(dir = dir, capBytes = 1_000_000L, scope = CoroutineScope(Dispatchers.IO)) {
            noisePngBytesOver16MB() // 单写 ≥ TRIM_SCAN_BYTES_DELTA：字节门首写即开
        }
        assertNotNull(store.load("https://e.com/oversize.png"))
        // 门开 → 全扫 → 超帽逐个删 mtime 最旧至帽内：stale（1.1MB）与大图（≈19.7MB）均删
        withTimeout(20_000) { while (stale.exists() || dir.listFiles()!!.isNotEmpty()) delay(10) }
        assertFalse(stale.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    // ── 失败负缓存退避（自检轮 4 R4-9，iOS RemoteImageLoader 同参互锚：TTL 60s/帽 300s） ──

    @Test
    fun failureNegativeCacheBackoffGrowsAndCaps(): Unit = runBlocking {
        var now = 0L
        val fetchCount = AtomicInteger(0)
        val store = MediaCacheStore(
            dir = tempDir(),
            scope = CoroutineScope(Dispatchers.IO),
            nowMillis = { now },
        ) {
            fetchCount.incrementAndGet()
            null
        }
        val url = "https://e.com/dead.png"
        // 退避窗随连续失败数生长：60/120/180/240/300（=帽）——每轮「窗满放行取数失败 →
        // 窗内一瞬拦截不启网络 → 再窗满」推进；帽语义由循环后第 7 次失败（无帽应 420s）钉死
        val windows = longArrayOf(60_000L, 120_000L, 180_000L, 240_000L, 300_000L, 300_000L)
        for ((i, window) in windows.withIndex()) {
            assertNull(store.load(url)) // 无记录/窗已过 → 放行（第 i+1 次失败记账）
            assertEquals(i + 1, fetchCount.get())
            now += window - 1 // 退避窗内最后一瞬：直回 null 不查盘不启网络
            assertNull(store.load(url))
            assertEquals(i + 1, fetchCount.get())
            now += 1
        }
        assertEquals(6, fetchCount.get())
        // 第 7 次失败：循环推进已过第 6 次失败的窗满沿；连续数=7 无帽窗=420s > 帽——
        // 帽封顶 300s 即放行（帽语义钉死，无帽实现此断言必红）
        assertNull(store.load(url))
        assertEquals(7, fetchCount.get())
        now += MEDIA_FAILURE_BACKOFF_CAP_MS - 1
        assertNull(store.load(url)) // 帽封顶：300s 内不放行
        assertEquals(7, fetchCount.get())
        now += 1
        assertNull(store.load(url)) // 窗满重开（死链自动重试面）
        assertEquals(8, fetchCount.get())
    }

    // ── 磁盘坏文件自愈 + 原子写（自检轮 4 R4-6，对齐 iOS MediaDiskCache .atomic 写） ──

    @Test
    fun corruptDiskEntrySelfHealsOnDecodeFailure(): Unit = runBlocking {
        val dir = tempDir()
        var now = 0L
        var dead = false
        val fetchCount = AtomicInteger(0)
        val store = MediaCacheStore(
            dir = dir,
            memoryCapBytes = 1L, // 单条超帽仍保最新（evictOrder 最近一条恒保留）：第二键把首键挤出内存
            scope = CoroutineScope(Dispatchers.IO),
            nowMillis = { now },
        ) { url ->
            fetchCount.incrementAndGet()
            when {
                url.contains("other.png") -> widePngBytes() // 挤内存用的第二键（成功）
                dead -> null
                else -> tinyPngBytes()
            }
        }
        val url = "https://e.com/corrupt.png"
        // 首载成功落盘（异步写盘等落定；R4-6 原子写瞬时 .tmp 不认，只等 .img 产物）
        assertNotNull(store.load(url))
        val diskFile = withTimeout(5_000) {
            while (dir.listFiles()!!.none { it.name.endsWith(".img") }) delay(10)
            dir.listFiles()!!.single { it.name.endsWith(".img") }
        }
        assertEquals(64, diskFile.nameWithoutExtension.length) // sha256hex 命名
        // 覆写为坏字节并把位图逐出内存（第二键挤掉第一条）
        diskFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        assertNotNull(store.load("https://e.com/other.png"))
        // 再载目标键：内存必失 → 磁盘命中但解码失败 → 坏文件自愈删除 → 网络取数失败记账
        dead = true
        assertNull(store.load(url))
        assertEquals(3, fetchCount.get()) // 取数#1=首载、#2=第二键挤内存、#3=本次失败
        assertFalse(diskFile.exists()) // 坏条目已删（不占磁盘帽、不再每次进视口白付 IO+解码）
        // 退避窗满后重取成功：原子写重落合法字节
        dead = false
        now += MEDIA_FAILURE_TTL_MS
        assertNotNull(store.load(url))
        assertEquals(4, fetchCount.get())
        withTimeout(5_000) { while (!diskFile.exists()) delay(10) }
        assertNotNull(store.decodeForDisplay(diskFile.readBytes())) // 重写的是可解码字节
    }

    /** ≥16MB 随机噪点 PNG（字节门单写触发样张；随机数据不可压缩，PNG 体积≈原始字节，实测 19.68MB）。 */
    private fun noisePngBytesOver16MB(): ByteArray {
        val image = BufferedImage(2560, 2560, BufferedImage.TYPE_INT_RGB)
        val data = (image.raster.dataBuffer as DataBufferInt).data
        val random = Random(42)
        for (i in data.indices) data[i] = random.nextInt()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
