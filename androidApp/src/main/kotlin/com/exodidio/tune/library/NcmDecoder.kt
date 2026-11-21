package com.exodidio.tune.library

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 网易云 .ncm 解码（对照 nondanee/ncmdump 的流程）：
 * CTENFDAM 头 -> XOR 0x64 + AES-ECB 解出 RC4 密钥 -> XOR 0x63 + Base64 + AES-ECB 解元数据
 * -> 跳过 CRC/封面段 -> RC4 流式解出 mp3/flac。全程流式，不整文件入内存。
 */
internal object NcmDecoder {
    private val CoreHeader = "CTENFDAM".toByteArray(Charsets.US_ASCII)
    private val KeySeed = "hzHRAmso5kInbaxW".toByteArray(Charsets.US_ASCII)
    private val MetaKey = "#14ljk_!\\]&0U<'(".toByteArray(Charsets.US_ASCII)
    private const val Key = 0x64.toByte()
    private const val Meta = 0x63.toByte()
    private const val ChunkSize = 0x8000

    private val json = Json { ignoreUnknownKeys = true }

    data class Metadata(
        val title: String = "",
        val artists: List<String> = emptyList(),
        val album: String = "",
        val format: String = "",
        val durationMs: Long = 0L,
    )

    data class Decoded(
        val audio: File,
        val cover: File?,
        val metadata: Metadata,
    )

    fun isNcm(name: String?): Boolean = name?.endsWith(".ncm", ignoreCase = true) == true

    suspend fun decode(
        context: Context,
        uri: Uri,
        outputDir: File,
        baseName: String,
    ): Decoded = BufferedInputStream(context.contentResolver.openInputStream(uri) ?: error("无法打开文件"))
        .use { input -> decode(input, outputDir, baseName) }

    suspend fun decode(input: InputStream, outputDir: File, baseName: String): Decoded {
        val header = readFully(input, 8)
        require(header.contentEquals(CoreHeader)) { "不是网易云 NCM 文件" }
        // 魔数后固定还有 2 字节占位。
        readFully(input, 2)

        val keyLength = readLeInt(input)
        require(keyLength in 1..(1 shl 24)) { "密钥段长度异常" }
        val keyData = unpadPkcs7(aesDecrypt(xorBytes(readFully(input, keyLength), Key), KeySeed))
        require(keyData.size > 17) { "密钥段无效" }
        val keyBox = buildKeyBox(keyData.copyOfRange(17, keyData.size))

        val metaLength = readLeInt(input)
        require(metaLength in 0..(1 shl 24)) { "元数据长度异常" }
        val metadata = parseMetadata(readFully(input, metaLength))

        // 元数据后：5 字节固定段 -> 封面区总长(4) -> 封面长度(4) -> 封面 -> 区尾填充。
        readFully(input, 5)
        val imageSpace = readLeInt(input)
        val imageSize = readLeInt(input)
        require(imageSpace in 0..(1 shl 26) && imageSize in 0..(1 shl 26)) { "封面长度异常" }
        val coverBytes = if (imageSize > 0) readFully(input, imageSize) else ByteArray(0)
        val tail = imageSpace - imageSize
        if (tail > 0) readFully(input, tail)

        outputDir.mkdirs()
        var first = true
        var output: File? = null
        var outputStream: BufferedOutputStream? = null
        try {
            val buffer = ByteArray(ChunkSize)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                decryptAudio(buffer, read, keyBox)
                if (first) {
                    val format = sniffFormat(buffer, read) ?: metadata.format.ifBlank { "mp3" }
                    output = File(outputDir, "$baseName.${format.lowercase()}")
                    outputStream = BufferedOutputStream(output.outputStream())
                    first = false
                }
                outputStream?.write(buffer, 0, read)
            }
            outputStream?.flush()
        } finally {
            runCatching { outputStream?.close() }
        }
        val audio = output ?: error("音频段为空")
        val cover = writeCover(outputDir, baseName, coverBytes)
        return Decoded(audio, cover, metadata)
    }

    private fun parseMetadata(raw: ByteArray): Metadata {
        if (raw.isEmpty()) return Metadata()
        return runCatching {
            val plain = xorBytes(raw, Meta)
            val payload = if (plain.size > 22) plain.copyOfRange(22, plain.size) else ByteArray(0)
            if (payload.isEmpty()) return@runCatching Metadata()
            val encrypted = java.util.Base64.getMimeDecoder().decode(payload)
            val decrypted = unpadPkcs7(aesDecrypt(encrypted, MetaKey))
            val text = decrypted.toString(Charsets.UTF_8)
            // 元数据明文以 "music:" 开头，再后面才是 JSON。
            val jsonText = if (text.startsWith("music:")) text.substring(6) else text.substring(text.indexOf('{').coerceAtLeast(0))
            val root = json.parseToJsonElement(jsonText).jsonObject
            Metadata(
                title = root.string("musicName"),
                artists = parseArtists(root["artist"]),
                album = root.string("album"),
                format = root.string("format"),
                durationMs = normalizeDuration(root["duration"]?.jsonPrimitive?.longOrNull ?: 0L),
            )
        }.getOrDefault(Metadata())
    }

    private fun parseArtists(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { entry ->
            when (entry) {
                is JsonArray -> entry.firstOrNull()?.jsonPrimitive?.contentOrNull
                is JsonObject -> entry.string("name").ifBlank { null }
                else -> entry.jsonPrimitive.contentOrNull
            }?.takeIf { it.isNotBlank() }
        }
        else -> emptyList()
    }

    private fun normalizeDuration(value: Long): Long = when {
        value <= 0L -> 0L
        // NCM 元数据里的 duration 是毫秒；极小的数按秒处理。
        value < 10_000L -> value * 1000L
        else -> value
    }

    private fun writeCover(outputDir: File, baseName: String, bytes: ByteArray): File? {
        if (bytes.size > 4) {
            val file = File(outputDir, "$baseName.cover.jpg")
            file.writeBytes(bytes)
            return file
        }
        return null
    }

    private fun sniffFormat(buffer: ByteArray, length: Int): String? {
        if (length < 4) return null
        val tag = String(buffer, 0, 4, Charsets.US_ASCII)
        return when {
            tag == "fLaC" -> "flac"
            tag.startsWith("ID3") -> "mp3"
            tag == "OggS" -> "ogg"
            tag == "RIFF" -> "wav"
            (buffer[0].toInt() and 0xFF) == 0xFF -> "mp3"
            else -> null
        }
    }

    private fun buildKeyBox(key: ByteArray): ByteArray {
        val box = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            val swap = box[i]
            j = (j + (swap.toInt() and 0xFF) + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            box[i] = box[j]
            box[j] = swap
        }
        return box
    }

    private fun decryptAudio(buffer: ByteArray, length: Int, keyBox: ByteArray) {
        for (i in 0 until length) {
            val j = (i + 1) and 0xFF
            val index = (keyBox[j].toInt() and 0xFF).let { first ->
                (first + (keyBox[(first + j) and 0xFF].toInt() and 0xFF)) and 0xFF
            }
            buffer[i] = (buffer[i].toInt() xor (keyBox[index].toInt() and 0xFF)).toByte()
        }
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "AES 数据长度无效" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun unpadPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 0xFF
        return if (pad in 1..16 && pad <= data.size && data.takeLast(pad).all { (it.toInt() and 0xFF) == pad }) {
            data.copyOfRange(0, data.size - pad)
        } else {
            data
        }
    }

    private fun xorBytes(data: ByteArray, key: Byte): ByteArray {
        for (i in data.indices) data[i] = (data[i].toInt() xor key.toInt()).toByte()
        return data
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) error("文件已截断")
            offset += read
        }
        return buffer
    }

    private fun readLeInt(input: InputStream): Int {
        val bytes = readFully(input, 4)
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
}
