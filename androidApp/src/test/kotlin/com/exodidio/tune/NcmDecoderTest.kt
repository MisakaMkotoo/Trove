package com.exodidio.tune

import com.exodidio.tune.library.NcmDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NcmDecoderTest {

    private val keySeed = "hzHRAmso5kInbaxW".toByteArray(Charsets.US_ASCII)
    private val metaKey = "#14ljk_!\\]&0U<'(".toByteArray(Charsets.US_ASCII)

    @Test
    fun decodesSyntheticNcmAndMetadata() = runTest {
        val audio = "fLaC".toByteArray(Charsets.US_ASCII) + Random(7).nextBytes(70_000)
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33)
        val ncm = buildNcm(audio, cover)
        val dir = createTempDir("ncm-test")

        val decoded = NcmDecoder.decode(ByteArrayInputStream(ncm), dir, "sample")

        assertArrayEquals(audio, decoded.audio.readBytes())
        assertEquals("晴天", decoded.metadata.title)
        assertEquals(listOf("周杰伦"), decoded.metadata.artists)
        assertEquals("叶惠美", decoded.metadata.album)
        assertEquals("flac", decoded.audio.extension)
        assertArrayEquals(cover, decoded.cover!!.readBytes())
    }

    private fun buildNcm(audio: ByteArray, cover: ByteArray): ByteArray {
        val rc4Key = ByteArray(16) { it.toByte() }
        val keyPlain = ("neteasecloudmusic" + String(rc4Key, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
        val keyBlock = xor(aesEncrypt(pad(keyPlain), keySeed), 0x64)

        val metadataJson = """{"musicName":"晴天","album":"叶惠美","artist":[["周杰伦",6452]],"format":"flac","duration":269000}"""
        val metaPlain = ("music:" + metadataJson).toByteArray(Charsets.UTF_8)
        val metaBlock = xor(
            ("163 key(Don't modify):" + Base64.getEncoder().encodeToString(aesEncrypt(pad(metaPlain), metaKey)))
                .toByteArray(Charsets.US_ASCII),
            0x63,
        )

        val imageSpace = cover.size + 8
        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0))
        out.write(leInt(keyBlock.size))
        out.write(keyBlock)
        out.write(leInt(metaBlock.size))
        out.write(metaBlock)
        out.write(ByteArray(5))
        out.write(leInt(imageSpace))
        out.write(leInt(cover.size))
        out.write(cover)
        out.write(ByteArray(imageSpace - cover.size))
        out.write(encryptAudio(audio, rc4Key))
        return out.toByteArray()
    }

    private fun encryptAudio(audio: ByteArray, key: ByteArray): ByteArray {
        val s = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            j = (j + (s[i].toInt() and 0xFF) + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val swap = s[i]; s[i] = s[j]; s[j] = swap
        }
        val stream = ByteArray(audio.size) { index ->
            val k = (index + 1) and 0xFF
            val first = s[k].toInt() and 0xFF
            s[(first + (s[(first + k) and 0xFF].toInt() and 0xFF)) and 0xFF]
        }
        return ByteArray(audio.size) { (audio[it].toInt() xor stream[it].toInt()).toByte() }
    }

    private fun pad(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        return data + ByteArray(pad) { pad.toByte() }
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun xor(data: ByteArray, key: Int): ByteArray = ByteArray(data.size) {
        (data[it].toInt() xor key).toByte()
    }

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun createTempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }
}
