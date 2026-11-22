package com.exodidio.tune.sync

import java.io.File

/**
 * 原子落盘：先写同目录的 .tmp，再改名覆盖目标文件。
 * 进程写入途中被杀只会留下可丢弃的临时文件，不会把原 JSON 截断成半份。
 */
internal fun File.writeTextAtomically(text: String) {
    val target = absoluteFile
    val dir = target.parentFile ?: error("文件没有父目录：$target")
    if (!dir.isDirectory && !dir.mkdirs()) error("无法创建目录：$dir")
    val temp = File(dir, "${target.name}.tmp")
    try {
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            if (!target.delete() || !temp.renameTo(target)) error("无法重命名 ${temp.name}")
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}
