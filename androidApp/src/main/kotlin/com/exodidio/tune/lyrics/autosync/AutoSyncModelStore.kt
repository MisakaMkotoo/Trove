package com.exodidio.tune.lyrics.autosync

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Resolves installed AutoSync model packs from app-private storage.
 * A language is installed when its folder holds `manifest.json` plus the ONNX
 * file that manifest names. Models are never bundled in the APK.
 */
internal object AutoSyncModelStore {
    private const val TAG = "TuneAutoSync"
    private const val ROOT = "autosync"

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun languageDirectory(context: Context, language: String): File = File(root(context), language)

    fun installedLanguages(context: Context): List<String> {
        val root = root(context)
        val children = root.listFiles() ?: return emptyList()
        return children.asSequence()
            .filter { it.isDirectory && !it.name.contains('.') }
            .map { it.name }
            .filter { language ->
                val directory = languageDirectory(context, language)
                val manifest = manifest(context, language) ?: return@filter false
                File(directory, manifest.modelFileName).isFile
            }
            .sorted()
            .toList()
    }

    fun manifest(context: Context, language: String): AutoSyncManifest? {
        val file = File(languageDirectory(context, language), "manifest.json")
        if (!file.isFile) return null
        return runCatching { AutoSyncManifest.parse(file.readText()) }
            .onFailure { Log.w(TAG, "manifest unreadable for $language", it) }
            .getOrNull()
    }

    fun modelPath(context: Context, language: String): String? {
        val manifest = manifest(context, language) ?: return null
        val model = File(languageDirectory(context, language), manifest.modelFileName)
        return model.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    fun timelineModelKey(context: Context, language: String): String? {
        val directory = languageDirectory(context, language)
        val manifestFile = File(directory, "manifest.json")
        val modelPath = modelPath(context, language) ?: return null
        val model = File(modelPath)
        val revision = if (language == "zh") "cif-buffer-v2" else "ctc-v2"
        val identity = "$revision|${manifestFile.readText()}|${model.length()}|${model.lastModified()}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun timelineModelKey(context: Context, languages: Collection<String>): String? {
        if (languages.isEmpty()) return null
        val parts = languages.sorted().map { timelineModelKey(context, it) ?: return null }
        return MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun writeManifest(context: Context, language: String, manifest: JSONObject) {
        val directory = languageDirectory(context, language)
        directory.mkdirs()
        File(directory, "manifest.json").writeText(manifest.toString())
    }

    fun delete(context: Context, language: String) {
        languageDirectory(context, language).deleteRecursively()
        AutoSyncTimelineStore.clearLanguage(context, language)
        OnnxCtcEngine.releaseAll()
        ZhAlignmentEngine.releaseAll()
    }
}
