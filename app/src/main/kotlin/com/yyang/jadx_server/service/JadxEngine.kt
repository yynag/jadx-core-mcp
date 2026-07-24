package com.yyang.jadx_server.service

import com.yyang.jadx_server.cache.JadxCacheManager
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaMethod
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JADX SDK Engine wrapper (`JadxEngine`).
 *
 * Responsibilities:
 * 1. Encapsulate `jadx-core` SDK loading and parsing.
 * 2. Maintain symbol table index cache (`classCache`) for instant class lookup.
 * 3. Implement lazy loading and on-demand decompilation strategy.
 * 4. Integrate `JadxCacheManager` for disk and SoftReference caching.
 * 5. Integrate configurable single-class decompilation timeout protection (default: 20s).
 * 6. Thread-safe operations protected by `ReentrantReadWriteLock`.
 */
class JadxEngine {

    /** Read/Write Lock: Protect JADX AST structure and concurrent HTTP queries from race conditions */
    private val rwLock = ReentrantReadWriteLock()
    private var decompileExecutor = Executors.newCachedThreadPool()

    private var decompiler: JadxDecompiler? = null
    private val classCache = ConcurrentHashMap<String, JavaClass>()
    private var cachedResContainers: List<jadx.core.xmlgen.ResContainer>? = null
    
    var currentApkPath: String? = null
        private set

    var classesCount: Int = 0
        private set

    var cacheManager: JadxCacheManager? = null
        private set

    fun loadApk(apkPath: String) {
        rwLock.write {
            unloadApkInternal(clearCache = false)

            val args = JadxArgs()
            val inputFiles = mutableListOf<File>()
            if (apkPath.contains(",")) {
                val paths = apkPath.split(",")
                for (p in paths) {
                    val f = File(p.trim())
                    if (f.exists()) inputFiles.add(f)
                }
            } else {
                val file = File(apkPath)
                if (!file.exists()) {
                    throw IllegalArgumentException("APK file not found: $apkPath")
                }
                if (file.isDirectory) {
                    val apks = file.listFiles { f -> f.name.endsWith(".apk", true) }
                    if (apks != null) inputFiles.addAll(apks)
                } else if (file.name.endsWith(".apk", true)) {
                    inputFiles.add(file)
                    val parentDir = file.parentFile
                    if (parentDir != null && parentDir.isDirectory) {
                        val otherSplits = parentDir.listFiles { f -> 
                            f.name.endsWith(".apk", true) && 
                            f.name != file.name && 
                            (f.name.startsWith("split_") || f.name.startsWith("config.")) 
                        }
                        if (otherSplits != null) inputFiles.addAll(otherSplits)
                    }
                } else {
                    inputFiles.add(file)
                }
            }

            if (inputFiles.isEmpty()) {
                throw IllegalArgumentException("Failed to resolve valid APK files from path: $apkPath")
            }

            /**
             * Split APK priority sorting to ensure base.apk is always listed first.
             */
            inputFiles.sortWith { f1, f2 ->
                val name1 = f1.name.lowercase()
                val name2 = f2.name.lowercase()
                when {
                    name1 == name2 -> 0
                    name1.startsWith("base.apk") || name1 == "base.apk" -> -1
                    name2.startsWith("base.apk") || name2 == "base.apk" -> 1
                    name1.startsWith("split_") && !name2.startsWith("split_") -> 1
                    !name1.startsWith("split_") && name2.startsWith("split_") -> -1
                    else -> name1.compareTo(name2)
                }
            }

            val mainFile = inputFiles[0]
            cacheManager = JadxCacheManager(mainFile)

            args.inputFiles = inputFiles
            args.isSkipResources = false
            args.isShowInconsistentCode = true
            args.isDeobfuscationOn = false
            args.pluginOptions = mapOf("dex-input.verify-checksum" to "no")

            println("[JadxEngine] Loading files: ${inputFiles.map { it.name }}...")
            val startTime = System.currentTimeMillis()
            
            val newDecompiler = JadxDecompiler(args)
            newDecompiler.load() 
            
            val loadedClasses = newDecompiler.classes
            classesCount = loadedClasses.size
            
            classCache.clear()
            for (javaClass in loadedClasses) {
                classCache[javaClass.fullName] = javaClass
            }
            
            this.decompiler = newDecompiler
            this.currentApkPath = apkPath
            val cost = System.currentTimeMillis() - startTime
            println("[JadxEngine] Initialization completed in ${cost}ms. Loaded $classesCount classes.")
        }
    }

    fun unloadApk(clearCache: Boolean = false) {
        rwLock.write {
            unloadApkInternal(clearCache)
        }
    }

    private fun unloadApkInternal(clearCache: Boolean) {
        if (decompiler != null) {
            println("[JadxEngine] Unloading previous APK: $currentApkPath")
            classCache.clear()
            cachedResContainers = null
            classesCount = 0
            
            /**
             * Shutdown decompilation thread pool to kill hung AST traversal threads.
             */
            try {
                decompileExecutor.shutdownNow()
            } catch (e: Exception) {
                // Ignore
            }
            decompileExecutor = Executors.newCachedThreadPool()

            try {
                decompiler?.close()
            } catch (e: Exception) {
                // Ignore IO exception
            }
            decompiler = null
            currentApkPath = null
            
            if (clearCache) {
                cacheManager?.clearCache()
            }
            cacheManager = null
            System.gc()
        }
    }

    val defaultTimeoutSeconds: Long = System.getProperty("jadx.decompile.timeout")?.toLongOrNull()
        ?: System.getenv("JADX_DECOMPILE_TIMEOUT")?.toLongOrNull()
        ?: 20L

    /**
     * Retrieve Java source code for specified class with configurable timeout protection.
     */
    fun getClassSource(javaClass: JavaClass, timeoutSeconds: Long? = null): String {
        return rwLock.read {
            val cached = cacheManager?.getCachedSource(javaClass.fullName)
            if (cached != null) {
                return@read cached
            }

            val effectiveTimeout = (timeoutSeconds?.takeIf { it > 0 }) ?: defaultTimeoutSeconds

            val future = decompileExecutor.submit<String> {
                javaClass.code
            }

            try {
                val source = future.get(effectiveTimeout, TimeUnit.SECONDS)
                cacheManager?.saveCachedSource(javaClass.fullName, source)
                source
            } catch (e: TimeoutException) {
                future.cancel(true)
                """
                /* [JADX WARNING] Decompilation timed out (${effectiveTimeout}s) for class ${javaClass.fullName}.
                   This class may contain obfuscation patterns causing infinite loops in AST generation. */
                public class ${javaClass.name} {
                    // Decompilation timed out.
                }
                """.trimIndent()
            } catch (e: Exception) {
                "/* Decompilation error: ${e.message} */"
            }
        }
    }

    /**
     * Retrieve Smali representation of specified class.
     */
    fun getClassSmali(javaClass: JavaClass): String {
        return rwLock.read {
            val cached = cacheManager?.getCachedSmali(javaClass.fullName)
            if (cached != null) return@read cached
            
            val smali = javaClass.smali
            cacheManager?.saveCachedSmali(javaClass.fullName, smali)
            smali
        }
    }

    /**
     * Retrieve source code snippet for a specific method within a class.
     */
    fun getMethodSourceCode(javaClass: JavaClass, methodName: String): String {
        return rwLock.read {
            val fullSource = getClassSource(javaClass)
            val targetMethod = javaClass.methods.find { 
                it.name == methodName || it.methodNode.methodInfo.shortId.contains(methodName) 
            }
            if (targetMethod != null) {
                val nodeCode = targetMethod.methodNode.codeStr
                if (!nodeCode.isNullOrEmpty()) {
                    return@read nodeCode
                }
            }
            extractMethodSnippetFromClassSource(fullSource, methodName)
        }
    }

    private fun extractMethodSnippetFromClassSource(source: String, methodName: String): String {
        val lines = source.lines()
        val startIndex = lines.indexOfFirst { it.contains(" $methodName(") || it.contains(" $methodName ") }
        if (startIndex == -1) return source
        val result = mutableListOf<String>()
        var braceCount = 0
        var foundBrace = false
        for (i in startIndex until lines.size) {
            val line = lines[i]
            result.add(line)
            if (line.contains("{")) {
                braceCount += line.count { it == '{' }
                foundBrace = true
            }
            if (line.contains("}")) {
                braceCount -= line.count { it == '}' }
            }
            if (foundBrace && braceCount <= 0) {
                break
            }
        }
        return result.joinToString("\n")
    }

    fun getClassByName(className: String): JavaClass? {
        return rwLock.read { classCache[className] }
    }
    
    fun getAllClassNames(offset: Int, count: Int): List<String> {
        return rwLock.read {
            val allNames = classCache.keys().toList().sorted()
            if (offset >= allNames.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, allNames.size) else allNames.size
            allNames.subList(offset, toIndex)
        }
    }

    private fun checkEngineReady() {
        if (decompiler == null) {
            throw IllegalStateException("Engine is not ready, please load an APK first.")
        }
    }

    fun getManifest(): String {
        return rwLock.read {
            checkEngineReady()
            var fallbackManifest = ""
            for (res in decompiler!!.resources) {
                if (res.originalName == "AndroidManifest.xml" || res.deobfName == "AndroidManifest.xml") {
                    val container = res.loadContent()
                    if (container != null && container.text != null) {
                        val xml = container.text.codeStr
                        if (fallbackManifest.isEmpty()) {
                            fallbackManifest = xml
                        }
                        if (!xml.contains("split=\"")) {
                            return@read xml
                        }
                    }
                }
            }
            fallbackManifest
        }
    }

    private fun collectResContainers(container: jadx.core.xmlgen.ResContainer, list: MutableList<jadx.core.xmlgen.ResContainer>) {
        if (container.subFiles != null && container.subFiles.isNotEmpty()) {
            for (sub in container.subFiles) {
                collectResContainers(sub, list)
            }
        } else {
            list.add(container)
        }
    }

    /**
     * Extract and cache full list of resource containers.
     */
    private fun getAllResContainers(): List<jadx.core.xmlgen.ResContainer> {
        val cached = cachedResContainers
        if (cached != null) return cached

        val list = mutableListOf<jadx.core.xmlgen.ResContainer>()
        if (decompiler != null) {
            for (res in decompiler!!.resources) {
                val container = res.loadContent()
                if (container != null) {
                    collectResContainers(container, list)
                }
            }
        }
        cachedResContainers = list
        return list
    }

    fun getResourceFile(name: String): String {
        return rwLock.read {
            checkEngineReady()
            val containers = getAllResContainers()
            for (container in containers) {
                val resName = container.fileName ?: container.name
                if (resName == name || resName?.endsWith("/$name") == true || container.name == name) {
                    if (container.text != null) {
                        return@read container.text.codeStr
                    }
                }
            }
            ""
        }
    }

    fun getAllResourceFileNames(offset: Int, count: Int): List<String> {
        return rwLock.read {
            checkEngineReady()
            val names = mutableListOf<String>()
            val containers = getAllResContainers()
            for (container in containers) {
                val resName = container.fileName ?: container.name
                if (resName != null) {
                    names.add(resName)
                }
            }
            val allNames = names.filter { it.isNotEmpty() }.sorted()
            if (offset >= allNames.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, allNames.size) else allNames.size
            allNames.subList(offset, toIndex)
        }
    }

    /**
     * Parse strings.xml constant table.
     */
    fun getStrings(offset: Int, count: Int): List<Map<String, String>> {
        return rwLock.read {
            val strings = mutableListOf<Map<String, String>>()
            val stringsXml = getResourceFile("res/values/strings.xml")
            if (stringsXml.isNotEmpty()) {
                try {
                    val factory = DocumentBuilderFactory.newInstance()
                    factory.isNamespaceAware = false
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(ByteArrayInputStream(stringsXml.toByteArray(Charsets.UTF_8)))
                    val nodeList = doc.getElementsByTagName("string")
                    for (i in 0 until nodeList.length) {
                        val elem = nodeList.item(i) as Element
                        val name = elem.getAttribute("name")
                        val value = elem.textContent
                        if (name.isNotEmpty()) {
                            strings.add(mapOf("name" to name, "value" to value))
                        }
                    }
                } catch (e: Exception) {
                    val regex = Regex("""<string name="([^"]+)">([\s\S]*?)</string>""")
                    val matches = regex.findAll(stringsXml)
                    for (match in matches) {
                        strings.add(mapOf("name" to match.groupValues[1], "value" to match.groupValues[2]))
                    }
                }
            }
            if (offset >= strings.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, strings.size) else strings.size
            strings.subList(offset, toIndex)
        }
    }

    fun getMainActivity(): JavaClass? {
        return rwLock.read {
            val manifestXml = getManifest()
            if (manifestXml.isEmpty()) return@read null
            
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                val builder = factory.newDocumentBuilder()
                val document = builder.parse(ByteArrayInputStream(manifestXml.toByteArray(Charsets.UTF_8)))
                
                var targetClassName: String? = null
                val tags = listOf("activity", "activity-alias")
                for (tag in tags) {
                    val nodes = document.getElementsByTagName(tag)
                    for (i in 0 until nodes.length) {
                        val node = nodes.item(i) as Element
                        var isMain = false
                        var isLauncher = false
                        
                        val intentFilters = node.getElementsByTagName("intent-filter")
                        for (j in 0 until intentFilters.length) {
                            val filter = intentFilters.item(j) as Element
                            val actions = filter.getElementsByTagName("action")
                            for (k in 0 until actions.length) {
                                val action = actions.item(k) as Element
                                if (action.getAttribute("android:name") == "android.intent.action.MAIN") {
                                    isMain = true
                                }
                            }
                            val categories = filter.getElementsByTagName("category")
                            for (k in 0 until categories.length) {
                                val category = categories.item(k) as Element
                                if (category.getAttribute("android:name") == "android.intent.category.LAUNCHER") {
                                    isLauncher = true
                                }
                            }
                        }
                        
                        if (isMain && isLauncher) {
                            if (tag == "activity-alias") {
                                targetClassName = node.getAttribute("android:targetActivity")
                            } else {
                                targetClassName = node.getAttribute("android:name")
                            }
                            break
                        }
                    }
                    if (targetClassName != null) break
                }
                
                if (targetClassName != null) {
                    if (targetClassName.startsWith(".")) {
                        val root = document.getElementsByTagName("manifest").item(0) as? Element
                        val pkg = root?.getAttribute("package") ?: ""
                        targetClassName = pkg + targetClassName
                    } else if (!targetClassName.contains(".")) {
                        val root = document.getElementsByTagName("manifest").item(0) as? Element
                        val pkg = root?.getAttribute("package") ?: ""
                        targetClassName = "$pkg.$targetClassName"
                    }
                    return@read getClassByName(targetClassName)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    fun getMainApplicationPackage(): String {
        return rwLock.read {
            val manifest = getManifest()
            if (manifest.isEmpty()) return@read ""
            val pkgRegex = Regex("""package="([^"]+)"""")
            val pkgMatch = pkgRegex.find(manifest)
            pkgMatch?.groupValues?.get(1) ?: ""
        }
    }

    fun getMainApplicationClassesNames(): List<String> {
        return rwLock.read {
            val pkg = getMainApplicationPackage()
            if (pkg.isEmpty()) return@read emptyList()
            classCache.keys().toList().filter { it.startsWith(pkg) }.sorted()
        }
    }

    fun searchClassesByKeyword(term: String, pkg: String, searchIn: String, offset: Int, count: Int): List<Map<String, String>> {
        return rwLock.read {
            val results = mutableListOf<Map<String, String>>()
            val searchScopes = searchIn.split(",")
            val searchCode = searchScopes.contains("code")
            val searchClass = searchScopes.contains("class")

            val targetClasses = if (pkg.isNotEmpty()) {
                classCache.values.filter { it.fullName.startsWith(pkg) }
            } else {
                classCache.values
            }

            for (javaClass in targetClasses) {
                var matched = false
                if (searchClass && javaClass.fullName.contains(term, ignoreCase = true)) {
                    matched = true
                }
                if (!matched && searchCode && getClassSource(javaClass).contains(term, ignoreCase = true)) {
                    matched = true
                }

                if (matched) {
                    results.add(mapOf("class_name" to javaClass.fullName, "code" to getClassSource(javaClass)))
                }
                
                if (results.size > offset + count && count > 0) {
                    break
                }
            }

            if (offset >= results.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, results.size) else results.size
            results.subList(offset, toIndex)
        }
    }

    fun searchMethodByName(methodName: String): List<Map<String, String>> {
        return rwLock.read {
            val results = mutableListOf<Map<String, String>>()
            for (javaClass in classCache.values) {
                for (method in javaClass.methods) {
                    if (method.name.contains(methodName, ignoreCase = true)) {
                        results.add(mapOf(
                            "class_name" to javaClass.fullName,
                            "method" to method.methodNode.methodInfo.shortId
                        ))
                    }
                }
            }
            results
        }
    }

    /**
     * Cross references (XRefs): extract code snippets for usage locations.
     */
    fun getXrefsToClass(className: String, offset: Int, count: Int): List<Map<String, String>> {
        return rwLock.read {
            val javaClass = requireClass(className)
            val simpleName = javaClass.name
            val usage = javaClass.useIn
            if (offset >= usage.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, usage.size) else usage.size
            val pagedNodes = usage.subList(offset, toIndex)
            
            pagedNodes.map { node ->
                val parentClass = node.topParentClass
                val snippet = extractSnippetFromParent(parentClass, simpleName, className)
                mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
            }
        }
    }

    fun getXrefsToMethod(className: String, methodName: String, offset: Int, count: Int): List<Map<String, String>> {
        return rwLock.read {
            val javaClass = requireClass(className)
            val targetMethod = javaClass.methods.find { 
                it.name == methodName || it.methodNode.methodInfo.shortId.contains(methodName) 
            } ?: throw IllegalArgumentException("Method '$methodName' not found in class '$className'")
            
            val usage = targetMethod.useIn
            if (offset >= usage.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, usage.size) else usage.size
            val pagedNodes = usage.subList(offset, toIndex)

            pagedNodes.map { node ->
                val parentClass = node.topParentClass
                val snippet = extractSnippetFromParent(parentClass, methodName, targetMethod.name)
                mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
            }
        }
    }

    fun getXrefsToField(className: String, fieldName: String, offset: Int, count: Int): List<Map<String, String>> {
        return rwLock.read {
            val javaClass = requireClass(className)
            val targetField = javaClass.fields.find { it.name == fieldName }
                ?: throw IllegalArgumentException("Field '$fieldName' not found in class '$className'")
                
            val usage = targetField.useIn
            if (offset >= usage.size) return@read emptyList()
            val toIndex = if (count > 0) minOf(offset + count, usage.size) else usage.size
            val pagedNodes = usage.subList(offset, toIndex)

            pagedNodes.map { node ->
                val parentClass = node.topParentClass
                val snippet = extractSnippetFromParent(parentClass, fieldName, targetField.name)
                mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
            }
        }
    }

    private fun extractSnippetFromParent(parentClass: JavaClass, vararg targetKeywords: String): String {
        try {
            val source = getClassSource(parentClass)
            val lines = source.lines()
            for ((idx, line) in lines.withIndex()) {
                for (kw in targetKeywords) {
                    if (kw.isNotEmpty() && line.contains(kw)) {
                        return "Line ${idx + 1}: ${line.trim()}"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "Referenced in ${parentClass.fullName}"
    }

    fun requireClass(className: String?): JavaClass {
        requireNotNull(className) { "Missing required parameter 'class_name'" }
        return getClassByName(className)
            ?: throw IllegalArgumentException("Class not found: $className")
    }
}
