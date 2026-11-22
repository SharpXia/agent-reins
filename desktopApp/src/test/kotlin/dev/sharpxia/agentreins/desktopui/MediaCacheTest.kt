package dev.sharpxia.agentreins.desktopui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException
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
        // 磁盘往返：文件按 sha256(stableCacheKey) 命名落盘（写盘独立作用域异步完成）
        withTimeout(5_000) {
            while (dir.listFiles().isNullOrEmpty()) delay(10)
        }
        assertEquals(1, dir.listFiles()!!.size)
        // 文件名 = sha256hex(stableCacheKey(url))（64 位 hex）
        assertEquals(64, dir.listFiles()!!.single().nameWithoutExtension.length)
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
}
