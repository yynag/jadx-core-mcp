package com.yyang.jadx_server.cache

import java.io.File
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages disk and memory caching for decompiled Java source code and Smali bytecodes.
 * Aligned with JADX-GUI Code Cache strategy supporting DISK_WITH_CACHE, MEMORY, and DISK modes.
 * Employs SoftReference to protect JVM heap memory, allowing automatic GC eviction under memory pressure.
 */
class JadxCacheManager(
    private val targetFile: File,
    var mode: CodeCacheMode = CodeCacheMode.DISK_WITH_CACHE
) {

    private val cacheDir: File

    /** Memory soft-reference cache to prevent OOM during deep decompilation */
    private val memorySourceCache = ConcurrentHashMap<String, SoftReference<String>>()
    private val memorySmaliCache = ConcurrentHashMap<String, SoftReference<String>>()

    init {
        val parent = targetFile.parentFile ?: File(".")
        cacheDir = File(parent, "${targetFile.name}_jadx_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    private fun getSourceFile(className: String): File {
        val path = className.replace('.', File.separatorChar) + ".java"
        return File(File(cacheDir, "sources"), path)
    }

    private fun getSmaliFile(className: String): File {
        val path = className.replace('.', File.separatorChar) + ".smali"
        return File(File(cacheDir, "smali"), path)
    }

    /**
     * Retrieve Java source cache using soft references and disk fallback.
     */
    fun getCachedSource(className: String): String? {
        if (mode == CodeCacheMode.MEMORY || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val ref = memorySourceCache[className]
            val code = ref?.get()
            if (code != null) return code
        }

        if (mode == CodeCacheMode.DISK || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val f = getSourceFile(className)
            if (f.exists()) {
                val text = f.readText(Charsets.UTF_8)
                if (mode == CodeCacheMode.DISK_WITH_CACHE) {
                    memorySourceCache[className] = SoftReference(text)
                }
                return text
            }
        }

        return null
    }

    /**
     * Write Java source code to cache.
     */
    fun saveCachedSource(className: String, code: String) {
        if (mode == CodeCacheMode.MEMORY || mode == CodeCacheMode.DISK_WITH_CACHE) {
            memorySourceCache[className] = SoftReference(code)
        }

        if (mode == CodeCacheMode.DISK || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val f = getSourceFile(className)
            f.parentFile?.mkdirs()
            f.writeText(code, Charsets.UTF_8)
        }
    }

    /**
     * Retrieve Smali bytecode cache.
     */
    fun getCachedSmali(className: String): String? {
        if (mode == CodeCacheMode.MEMORY || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val ref = memorySmaliCache[className]
            val smali = ref?.get()
            if (smali != null) return smali
        }

        if (mode == CodeCacheMode.DISK || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val f = getSmaliFile(className)
            if (f.exists()) {
                val text = f.readText(Charsets.UTF_8)
                if (mode == CodeCacheMode.DISK_WITH_CACHE) {
                    memorySmaliCache[className] = SoftReference(text)
                }
                return text
            }
        }

        return null
    }

    /**
     * Write Smali bytecode to cache.
     */
    fun saveCachedSmali(className: String, smali: String) {
        if (mode == CodeCacheMode.MEMORY || mode == CodeCacheMode.DISK_WITH_CACHE) {
            memorySmaliCache[className] = SoftReference(smali)
        }

        if (mode == CodeCacheMode.DISK || mode == CodeCacheMode.DISK_WITH_CACHE) {
            val f = getSmaliFile(className)
            f.parentFile?.mkdirs()
            f.writeText(smali, Charsets.UTF_8)
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
