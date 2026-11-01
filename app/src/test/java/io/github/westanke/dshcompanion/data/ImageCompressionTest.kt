package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ImageCompression] 的压缩策略测试。
 *
 * 全程注入假的 [ImageCodec]：本模块没有 Robolectric，真的去调 `BitmapFactory`
 * 在 JVM 上会抛 "not mocked"。而这段代码真正的坑（质量阶梯走没走完、轮数会不会无限、
 * 已经够小的图有没有被白压一遍、压完 mediaType 与文件名对不对得上）全在策略层，
 * 与「怎么解码一张 JPEG」无关 —— 所以假 codec 完全够用，而且能精确控制每一轮产物大小。
 */
class ImageCompressionTest {

    /** 可控的假编解码器：记录每次调用，按测试给的方式产出字节。 */
    private class FakeCodec(
        private val size: ImageSize?,
        private val encode: (longEdge: Int, quality: Int) -> ByteArray?,
    ) : ImageCodec {

        val calls = mutableListOf<Pair<Int, Int>>()

        override fun bounds(bytes: ByteArray): ImageSize? = size

        override fun encodeJpeg(bytes: ByteArray, longEdge: Int, quality: Int): ByteArray? {
            calls += longEdge to quality
            return encode(longEdge, quality)
        }
    }

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    // ---------------------------------------------------------------- 阶梯与几何

    @Test
    fun `质量阶梯从 85 走到 45`() {
        assertEquals(listOf(85, 75, 65, 55, 45), imageQualityLadder())
    }

    @Test
    fun `长边夹取不放大`() {
        // 竖图：长边是高度。
        assertEquals(1600, fitLongEdge(ImageSize(1200, 4000)))
        // 已经比上限小：保持原样，放大只会让体积变大。
        assertEquals(800, fitLongEdge(ImageSize(800, 600)))
    }

    @Test
    fun `只有体积超了才压 长边不是触发条件`() {
        assertTrue("体积超了要压", needsImageCompression(MAX_ENCODED_IMAGE_BYTES + 1))
        assertTrue("正好卡在上限不算超", !needsImageCompression(MAX_ENCODED_IMAGE_BYTES))
        assertTrue("远小于上限当然不压", !needsImageCompression(294_000))
    }

    @Test
    fun `手机截图体积够小就不压 哪怕长边超过 1600`() {
        // 1080×2400 的截图（长边 2400 > 1600）只有 287.8 KiB，本来就能塞进 1 MiB 的帧。
        // 把它当「长边超标」重编码成 JPEG，等于在修 #12 的同时把 #11 的截图糊掉。
        val codec = FakeCodec(ImageSize(1080, 2400)) { _, _ -> fail("不该走到编码"); null }
        val source = bytes(294_000)

        val result = compressImageForWire(codec, source)

        assertSame("必须原样返回", source, result)
        assertTrue("一次编码都不该发生", codec.calls.isEmpty())
    }

    // ---------------------------------------------------------------- 不该压的图

    @Test
    fun `已经在阈值内的图返回同一个实例 一个字节都不动`() {
        // #11 的不回归条件：287.8 KiB 的 PNG 截图原本能发，压一遍只会掉画质。
        val codec = FakeCodec(ImageSize(1080, 2400)) { _, _ -> fail("不该走到编码"); null }
        val source = bytes(294_000)

        val result = compressImageForWire(codec, source)

        assertSame("必须原样返回同一个实例", source, result)
        assertTrue("一次编码都不该发生", codec.calls.isEmpty())
    }

    @Test
    fun `读不出尺寸就返回 null 表示这不是图片`() {
        val codec = FakeCodec(null) { _, _ -> fail("不该走到编码"); null }
        assertNull(compressImageForWire(codec, bytes(1000)))
    }

    @Test
    fun `尺寸非法也返回 null`() {
        val codec = FakeCodec(ImageSize(0, 100)) { _, _ -> fail("不该走到编码"); null }
        assertNull(compressImageForWire(codec, bytes(1000)))
    }

    // ---------------------------------------------------------------- 压得下来

    @Test
    fun `超体积的图在第一档质量就压到限内`() {
        // 质量 85 就够：每档质量产出 quality * 1000 字节，85 → 85000 ≤ 700 KiB。
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, quality -> bytes(quality * 1000) }
        val source = bytes(3 * 1024 * 1024)

        val result = compressImageForWire(codec, source)

        assertNotNull(result)
        assertTrue("产物要在限内", result!!.size.toLong() <= MAX_ENCODED_IMAGE_BYTES)
        assertEquals("只该试第一档", listOf(1600 to 85), codec.calls)
    }

    @Test
    fun `高熵内容要往下降质量 直到落进限内`() {
        // 质量 85/75 都超标，65 才够。
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, quality ->
            bytes(if (quality >= 75) (MAX_ENCODED_IMAGE_BYTES + 10_000).toInt() else 500_000)
        }
        val source = bytes(3 * 1024 * 1024)

        val result = compressImageForWire(codec, source)

        assertTrue(result!!.size.toLong() <= MAX_ENCODED_IMAGE_BYTES)
        assertEquals(listOf(1600 to 85, 1600 to 75, 1600 to 65), codec.calls)
    }

    @Test
    fun `压的时候长边夹到 1600`() {
        // 体积超了才进压缩，此时长边才作为目标尺寸起作用。
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, _ -> bytes(150_000) }
        val source = bytes(3 * 1024 * 1024)

        val result = compressImageForWire(codec, source)

        assertTrue("应该被压过（不是同一个实例）", result !== source)
        assertEquals("长边要夹到 1600", 1600, codec.calls.first().first)
    }

    // ---------------------------------------------------------------- 压不下来

    @Test
    fun `一轮质量阶梯走完仍超标就缩长边再来一轮`() {
        // 永远超标：第一轮 5 档全试完，长边 ×0.75 后再试。
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, _ -> bytes((MAX_ENCODED_IMAGE_BYTES + 1).toInt()) }
        val source = bytes(3 * 1024 * 1024)

        compressImageForWire(codec, source)

        val edges = codec.calls.map { it.first }.distinct()
        assertEquals("最多 3 轮", 3, edges.size)
        assertEquals("第一轮长边 1600", 1600, edges[0])
        assertEquals("第二轮 1600×0.75", 1200, edges[1])
        assertEquals("第三轮 1200×0.75", 900, edges[2])
        assertEquals("每轮 5 档质量", 15, codec.calls.size)
    }

    @Test
    fun `全都压不下来就返回最小的那个`() {
        // 每一档都**略超**限（所以整条阶梯走满 15 次），但一次比一次小：
        // 返回的应该是最后那个（最小的），而不是第一档或原图。
        var produced = 0
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, _ ->
            produced += 1
            bytes((MAX_ENCODED_IMAGE_BYTES + 50_000 - produced * 1_000).toInt())
        }
        val source = bytes(3 * 1024 * 1024)

        val result = compressImageForWire(codec, source)

        assertNotNull(result)
        assertEquals("阶梯要全部走完", 15, codec.calls.size)
        assertEquals("返回最小的那个", (MAX_ENCODED_IMAGE_BYTES + 50_000 - 15 * 1_000).toInt(), result!!.size)
        assertTrue("不该退回原图（原图更大）", result.size < source.size)
    }

    @Test
    fun `编码器全失败时退回原图 而不是返回 null`() {
        // null 的语义是「不是图片」，编码失败不是那个意思 —— 别让调用方误报「读不出尺寸」。
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, _ -> null }
        val source = bytes(3 * 1024 * 1024)

        val result = compressImageForWire(codec, source)

        assertSame("退回原图", source, result)
    }

    // ---------------------------------------------------------------- 媒体类型与文件名

    @Test
    fun `压过的图 mediaType 改成 jpeg 文件名也换成 jpg`() {
        val codec = FakeCodec(ImageSize(4000, 3000)) { _, quality -> bytes(quality * 1000) }
        val source = bytes(3 * 1024 * 1024)

        val prepared = prepareImageForWire(codec, source, "image/png", "Screenshot_2026.png")

        assertNotNull(prepared)
        assertEquals(COMPRESSED_IMAGE_MEDIA_TYPE, prepared!!.mediaType)
        assertEquals("Screenshot_2026.jpg", prepared.name)
    }

    @Test
    fun `没压的图 mediaType 与文件名都不动`() {
        val codec = FakeCodec(ImageSize(1080, 2400)) { _, _ -> fail("不该走到编码"); null }
        val source = bytes(294_000)

        val prepared = prepareImageForWire(codec, source, "image/png", "Screenshot_2026.png")

        assertNotNull(prepared)
        assertSame(source, prepared!!.bytes)
        assertEquals("image/png", prepared.mediaType)
        assertEquals("Screenshot_2026.png", prepared.name)
    }

    @Test
    fun `读不出尺寸时 prepare 返回 null`() {
        val codec = FakeCodec(null) { _, _ -> fail("不该走到编码"); null }
        assertNull(prepareImageForWire(codec, bytes(1000), "image/png", "x.png"))
    }

    @Test
    fun `文件名换扩展名`() {
        assertEquals("a.jpg", jpegName("a.png"))
        assertEquals("a.jpg", jpegName("a.jpeg"))
        assertEquals("截图 2026-11-01.jpg", jpegName("截图 2026-11-01.png"))
        // 没有扩展名：补一个，而不是把最后一个点当分隔符。
        assertEquals("noext.jpg", jpegName("noext"))
        // 隐藏文件没有扩展名可言。
        assertEquals(".nomedia.jpg", jpegName(".nomedia"))
        // 空名字要有个兜底，不能产出 ".jpg"。
        assertEquals("image.jpg", jpegName("   "))
        // 多点文件名：只换最后一段。
        assertEquals("a.b.jpg", jpegName("a.b.png"))
    }
}
