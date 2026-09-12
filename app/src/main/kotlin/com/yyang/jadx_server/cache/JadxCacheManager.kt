package com.yyang.jadx_server.cache

import java.io.File
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * WHY:
 * 1) 同路径多 Worker 共享 `{apkName}_jadx_cache` 会并发写撕裂源码；
 * 2) className 直接拼路径存在 `../` 穿越面；
 * 3) 无内容指纹时换包同名会读到脏缓存。
 * DECISION: soft+disk 固定两级；目录 = 主文件旁 + apk版本 + 实例隔离键 + 文件指纹；类名白名单 + normalize 校验。
 * EVIDENCE: 审查 K1/K2/L6；进程隔离卸载靠杀 JVM，磁盘缓存仍需按实例隔离。
 */
class JadxCacheManager(
    private val targetFile: File,
    /** Worker 实例隔离键（通常为 apk_id），避免同路径多开互写 */
    instanceKey: String,
    private val jadxVersion: String = "1.5.6"
) {
    val cacheDir: File

    private val memorySourceCache = ConcurrentHashMap<String, SoftReference<String>>()
    private val memorySmaliCache = ConcurrentHashMap<String, SoftReference<String>>()

    init {
        val parent = targetFile.parentFile ?: File(".")
        val fingerprint = fileFingerprint(targetFile)
        val safeInstance = instanceKey.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default" }
        // 结构: {apkName}_jadx_cache/jadx-{ver}/{instance}/{fingerprint}/
        cacheDir = File(
            parent,
            "${targetFile.name}_jadx_cache${File.separator}jadx-$jadxVersion${File.separator}$safeInstance${File.separator}$fingerprint"
        )
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    companion object {
        private val SAFE_CLASS_NAME = Regex("^[A-Za-z0-9_.$]+$")

        fun fileFingerprint(file: File): String {
            if (!file.exists() || !file.isFile) return "nofile"
            return "${file.length()}_${file.lastModified()}"
        }

        /**
         * Master unload(clear_cache) 时按主文件名删除整个 `{name}_jadx_cache` 根目录。
         * WHY: Worker 内指纹子目录 Master 不一定知道；整树删除最简单且与「用户要求清缓存」语义一致。
         */
        fun resolveCacheRoot(apkPath: String): File {
            val file = File(apkPath)
            val parent = file.parentFile ?: File(".")
            return File(parent, "${file.name}_jadx_cache")
        }
    }

    private fun assertSafeClassName(className: String) {
        require(SAFE_CLASS_NAME.matches(className)) {
            "Unsafe class name for cache path: $className"
        }
    }

    private fun resolveUnderCache(subDir: String, className: String, ext: String): File {
        assertSafeClassName(className)
        val relative = className.replace('.', File.separatorChar) + ext
        val base = File(cacheDir, subDir).canonicalFile
        val target = File(base, relative).canonicalFile
        require(target.path.startsWith(base.path + File.separator) || target.path == base.path) {
            "Cache path escape detected for class: $className"
        }
        return target
    }

    private fun getSourceFile(className: String): File = resolveUnderCache("sources", className, ".java")

    private fun getSmaliFile(className: String): File = resolveUnderCache("smali", className, ".smali")

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp.${ProcessHandle.current().pid()}")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun getCachedSource(className: String): String? {
        memorySourceCache[className]?.get()?.let { return it }
        val f = getSourceFile(className)
        if (f.exists()) {
            val text = f.readText(Charsets.UTF_8)
            memorySourceCache[className] = SoftReference(text)
            return text
        }
        return null
    }

    fun saveCachedSource(className: String, code: String) {
        memorySourceCache[className] = SoftReference(code)
        atomicWrite(getSourceFile(className), code)
    }

    fun getCachedSmali(className: String): String? {
        memorySmaliCache[className]?.get()?.let { return it }
        val f = getSmaliFile(className)
        if (f.exists()) {
            val text = f.readText(Charsets.UTF_8)
            memorySmaliCache[className] = SoftReference(text)
            return text
        }
        return null
    }

    fun saveCachedSmali(className: String, smali: String) {
        memorySmaliCache[className] = SoftReference(smali)
        atomicWrite(getSmaliFile(className), smali)
    }

    /**
     * DEX 字符串索引按 APK 指纹共享（不跟 apk_id），重启 Worker 可复用。
     * deobf 会改 class/method 别名，必须分文件。
     */
    fun stringIndexFile(deobf: Boolean): File {
        val parent = targetFile.parentFile ?: File(".")
        val fingerprint = fileFingerprint(targetFile)
        return File(
            parent,
            "${targetFile.name}_jadx_cache${File.separator}jadx-$jadxVersion${File.separator}_strings${File.separator}$fingerprint${File.separator}index-v2-deobf-$deobf.json"
        )
    }

    fun loadStringIndexJson(deobf: Boolean): String? {
        val f = stringIndexFile(deobf)
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun saveStringIndexJson(deobf: Boolean, json: String) {
        atomicWrite(stringIndexFile(deobf), json)
    }

    fun userRenamesFile(deobf: Boolean): File {
        val parent = targetFile.parentFile ?: File(".")
        val fingerprint = fileFingerprint(targetFile)
        return File(
            parent,
            "${targetFile.name}_jadx_cache${File.separator}jadx-$jadxVersion${File.separator}_renames${File.separator}$fingerprint${File.separator}user-renames-deobf-$deobf.json"
        )
    }

    fun loadUserRenamesJson(deobf: Boolean): String? {
        val f = userRenamesFile(deobf)
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun saveUserRenamesJson(deobf: Boolean, json: String) {
        atomicWrite(userRenamesFile(deobf), json)
    }

    fun evict(className: String) {
        memorySourceCache.remove(className)
        memorySmaliCache.remove(className)
        try {
            val src = getSourceFile(className)
            if (src.exists()) src.delete()
            val smali = getSmaliFile(className)
            if (smali.exists()) smali.delete()
        } catch (_: Exception) {
        }
    }

    fun clearCache() {
        memorySourceCache.clear()
        memorySmaliCache.clear()
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }
}
