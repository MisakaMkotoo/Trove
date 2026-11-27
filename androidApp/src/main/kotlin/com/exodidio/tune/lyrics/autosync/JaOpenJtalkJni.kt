package com.exodidio.tune.lyrics.autosync

/**
 * Top-level JNI declarations for the OpenJTalk G2P frontend.
 * Class name is `JaOpenJtalkJniKt`, matching the symbols in `ja_g2p_jni.cpp`.
 */
internal external fun nativeOpen(dictionaryDirectory: String): Long

internal external fun nativeClose(handle: Long)

/** Space-separated phonemes, exactly `pyopenjtalk.g2p(text)` (no boundary pau). */
internal external fun nativePhonemes(handle: Long, text: String): String?

/** Katakana reading, exactly `pyopenjtalk.g2p(text, kana=True)`. */
internal external fun nativeKana(handle: Long, text: String): String?

/** `surface\tpron` lines, exactly `pyopenjtalk.run_frontend(text)`. */
internal external fun nativeTokens(handle: Long, text: String): String?
