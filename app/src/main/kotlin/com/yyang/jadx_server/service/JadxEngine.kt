package com.yyang.jadx_server.service

import com.yyang.jadx_server.cache.JadxCacheManager
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import org.w3c.dom.Element
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

/**
 * JADX headless 引擎封装。
 *
 * WHY / DECISION 总览:
 * 1) load() 只建索引、不预反编译 —— jadx 全量 decompile 极慢且吃内存。
 * 2) 反编译在 rwLock **之外**执行 —— 锁内 future.get 会饿死 unload/其它写操作（审查 T1）。
 * 3) 有界线程池 + 同类 single-flight —— CachedThreadPool 无界会在超时无效时线程膨胀（T2）。
 * 4) 超时/失败抛 DecompileException —— 禁止 200 假源码（S0）。
 * 5) 类索引含 inner —— getClasses() 排除内部类会导致 Outer$Inner 查找失败（C1）。
 */
class JadxEngine(
    /** 缓存实例隔离键；Worker 传入 apk_id */
    private val cacheInstanceKey: String = "local"
) {

    private val log = LoggerFactory.getLogger(JadxEngine::class.java)
    private val rwLock = ReentrantReadWriteLock()

    // WHY: 有界池限制并行反编译；core=1 保证至少能跑，max 受 CPU 与环境变量约束
    private val poolSize = System.getenv("JADX_DECOMPILE_THREADS")?.toIntOrNull()?.coerceIn(1, 16)
        ?: min(4, max(1, Runtime.getRuntime().availableProcessors() / 2))
    private var decompileExecutor = Executors.newFixedThreadPool(poolSize)

    /** 同类反编译 single-flight，避免 N 请求同时 decompile 同一 ClassNode */
    private val inflightSource = ConcurrentHashMap<String, Future<String>>()
    private val inflightSmali = ConcurrentHashMap<String, Future<String>>()

    private var decompiler: JadxDecompiler? = null
    private val classCache = ConcurrentHashMap<String, JavaClass>()

    // WHY: 只缓存资源「名字列表」，内容按需 loadContent，避免大包一次灌满堆（R1）
    @Volatile private var resourceNameIndex: List<String>? = null

    var currentApkPath: String? = null
        private set

    var classesCount: Int = 0
        private set

    var cacheManager: JadxCacheManager? = null
        private set

    /** 解析后的主输入文件（用于 clear_cache 路径对齐） */
    var mainInputFile: File? = null
        private set

    val defaultTimeoutSeconds: Long = System.getProperty("jadx.decompile.timeout")?.toLongOrNull()
        ?: System.getenv("JADX_DECOMPILE_TIMEOUT")?.toLongOrNull()
        ?: 20L

    // WHY: 默认预算写死；单次由请求 max_scan/max_decompile/timeout 覆盖（Agent 决定）
    private val defaultMaxScan = 500
    private val defaultMaxDecompileOnSearch = 50
    private val searchTimeoutCapSec = 600L

    fun loadApk(apkPath: String) {
        rwLock.write {
            // 无论上次是否成功，先清干净半初始化状态（L2）
            unloadApkInternal(clearCache = false)

            val inputFiles = resolveInputFiles(apkPath)
            val mainFile = inputFiles[0]
            mainInputFile = mainFile

            // cacheManager 仅在 load 成功前创建；失败 finally 清理
            val cm = JadxCacheManager(mainFile, instanceKey = cacheInstanceKey)
            try {
                val args = JadxArgs()
                args.inputFiles = inputFiles
                args.isSkipResources = false
                args.isShowInconsistentCode = true
                args.isDeobfuscationOn = false
                // 限制 jadx 内部并行，与自建池叠加防线程风暴
                args.threadsCount = poolSize
                args.pluginOptions = mapOf("dex-input.verify-checksum" to "no")

                log.info("Loading files: {}", inputFiles.map { it.name })
                val startTime = System.currentTimeMillis()

                val newDecompiler = JadxDecompiler(args)
                newDecompiler.load()

                // WHY: getClassesWithInners 含内部类；getClasses 会丢 Outer$Inner
                val loadedClasses = newDecompiler.classesWithInners
                classesCount = loadedClasses.size

                classCache.clear()
                for (javaClass in loadedClasses) {
                    classCache[javaClass.fullName] = javaClass
                }

                this.decompiler = newDecompiler
                this.cacheManager = cm
                this.currentApkPath = apkPath
                resourceNameIndex = null
                log.info("Initialization completed in {}ms. Loaded {} classes.", System.currentTimeMillis() - startTime, classesCount)
            } catch (e: Exception) {
                try {
                    cm.clearCache()
                } catch (_: Exception) {
                }
                unloadApkInternal(clearCache = false)
                throw e
            }
        }
    }

    private fun resolveInputFiles(apkPath: String): MutableList<File> {
        val inputFiles = mutableListOf<File>()
        if (apkPath.contains(",")) {
            val missing = mutableListOf<String>()
            for (p in apkPath.split(",")) {
                val f = File(p.trim())
                if (f.exists()) inputFiles.add(f) else missing.add(p.trim())
            }
            // WHY: 静默 skip 缺失 split 会导致「半套包成功加载」难排查
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException("APK path(s) not found: ${missing.joinToString(", ")}")
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
        inputFiles.sortWith { f1, f2 ->
            val name1 = f1.name.lowercase()
            val name2 = f2.name.lowercase()
            when {
                name1 == name2 -> 0
                name1 == "base.apk" -> -1
                name2 == "base.apk" -> 1
                name1.startsWith("split_") && !name2.startsWith("split_") -> 1
                !name1.startsWith("split_") && name2.startsWith("split_") -> -1
                else -> name1.compareTo(name2)
            }
        }
        return inputFiles
    }

    fun unloadApk(clearCache: Boolean = false) {
        rwLock.write {
            unloadApkInternal(clearCache)
        }
    }

    private fun unloadApkInternal(clearCache: Boolean) {
        classCache.clear()
        resourceNameIndex = null
        classesCount = 0
        inflightSource.clear()
        inflightSmali.clear()

        try {
            decompileExecutor.shutdownNow()
            decompileExecutor.awaitTermination(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        decompileExecutor = Executors.newFixedThreadPool(poolSize)

        try {
            decompiler?.close()
        } catch (_: Exception) {
        }
        decompiler = null
        currentApkPath = null
        mainInputFile = null

        if (clearCache) {
            cacheManager?.clearCache()
        }
        cacheManager = null
    }

    private fun effectiveTimeout(timeoutSeconds: Long?): Long {
        return (timeoutSeconds?.takeIf { it > 0 }) ?: defaultTimeoutSeconds
    }

    /**
     * 锁外反编译核心。
     * WHY: 读锁内阻塞会导致 unload 写锁饿死；且 jadx cancel 不可靠，必须用超时 Future 断路。
     */
    fun getClassSource(javaClass: JavaClass, timeoutSeconds: Long? = null): String {
        val cached = rwLock.read { cacheManager?.getCachedSource(javaClass.fullName) }
        if (cached != null) return cached

        // 确认引擎仍存活并拿到稳定引用（短持锁）
        rwLock.read {
            checkEngineReady()
            classCache[javaClass.fullName]
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Class unloaded or missing: ${javaClass.fullName}", 404)
        }

        val timeout = effectiveTimeout(timeoutSeconds)
        val key = javaClass.fullName

        val future = inflightSource.computeIfAbsent(key) {
            decompileExecutor.submit(Callable {
                javaClass.code
            })
        }

        try {
            val source = future.get(timeout, TimeUnit.SECONDS)
            rwLock.read { cacheManager?.saveCachedSource(key, source) }
            return source
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw DecompileException(
                DecompileException.TIMEOUT,
                "Decompilation timed out (${timeout}s) for class $key. Class may be heavily obfuscated.",
                504
            )
        } catch (e: DecompileException) {
            throw e
        } catch (e: Exception) {
            val cause = e.cause ?: e
            if (cause is DecompileException) throw cause
            throw DecompileException(
                DecompileException.FAILED,
                "Decompilation failed for $key: ${cause.message}",
                500
            )
        } finally {
            inflightSource.remove(key, future)
        }
    }

    fun getClassSmali(javaClass: JavaClass, timeoutSeconds: Long? = null): String {
        val cached = rwLock.read { cacheManager?.getCachedSmali(javaClass.fullName) }
        if (cached != null) return cached

        rwLock.read {
            checkEngineReady()
            classCache[javaClass.fullName]
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Class unloaded or missing: ${javaClass.fullName}", 404)
        }

        val timeout = effectiveTimeout(timeoutSeconds)
        val key = javaClass.fullName
        val future = inflightSmali.computeIfAbsent(key) {
            decompileExecutor.submit(Callable { javaClass.smali })
        }
        try {
            val smali = future.get(timeout, TimeUnit.SECONDS)
            rwLock.read { cacheManager?.saveCachedSmali(key, smali) }
            return smali
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw DecompileException(
                DecompileException.TIMEOUT,
                "Smali generation timed out (${timeout}s) for class $key",
                504
            )
        } catch (e: DecompileException) {
            throw e
        } catch (e: Exception) {
            val cause = e.cause ?: e
            throw DecompileException(DecompileException.FAILED, "Smali failed for $key: ${cause.message}", 500)
        } finally {
            inflightSmali.remove(key, future)
        }
    }

    /**
     * WHY: methodNode.codeStr 会同步 decompile 且绕过超时；只能基于已超时保护的整类源码切片。
     * DECISION: 先 getClassSource(timeout)，再 brace 切片；找不到方法抛 NOT_FOUND，禁止退回整类。
     */
    fun getMethodSourceCode(javaClass: JavaClass, methodName: String, timeoutSeconds: Long? = null): String {
        val fullSource = getClassSource(javaClass, timeoutSeconds)
        val snippet = extractMethodSnippetFromClassSource(fullSource, methodName)
            ?: throw DecompileException(
                DecompileException.NOT_FOUND,
                "Method '$methodName' not found in decompiled source of ${javaClass.fullName}",
                404
            )
        return snippet
    }

    private fun extractMethodSnippetFromClassSource(source: String, methodName: String): String? {
        val lines = source.lines()
        val startIndex = lines.indexOfFirst { it.contains(" $methodName(") || it.contains(".$methodName(") }
        if (startIndex == -1) return null
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
            if (foundBrace && braceCount <= 0) break
        }
        return result.joinToString("\n")
    }

    fun getClassByName(className: String): JavaClass? {
        return rwLock.read {
            classCache[className]
                ?: decompiler?.searchJavaClassByOrigFullName(className)
                ?: decompiler?.searchJavaClassByAliasFullName(className)
        }
    }

    fun getAllClassNames(offset: Int, count: Int): List<String> {
        return rwLock.read {
            checkEngineReady()
            val allNames = classCache.keys().toList().sorted()
            pageList(allNames, offset, count)
        }
    }

    fun getClassNamesByPackage(pkg: String, offset: Int, count: Int): List<String> {
        return rwLock.read {
            checkEngineReady()
            val prefix = if (pkg.endsWith(".")) pkg else "$pkg."
            val allNames = classCache.keys().toList()
                .filter { it == pkg || it.startsWith(prefix) || it.startsWith(pkg) }
                .sorted()
            pageList(allNames, offset, count)
        }
    }

    fun countClassesByPackage(pkg: String): Int {
        return rwLock.read {
            if (pkg.isEmpty()) return@read classesCount
            val prefix = if (pkg.endsWith(".")) pkg else "$pkg."
            classCache.keys().toList().count { it == pkg || it.startsWith(prefix) || it.startsWith(pkg) }
        }
    }

    private fun checkEngineReady() {
        if (decompiler == null) {
            throw IllegalStateException("Engine is not ready, please load an APK first.")
        }
    }

    private fun secureXmlFactory(namespaceAware: Boolean): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        factory.isValidating = false
        // WHY: 恶意/怪异 APK 资源可触发 XXE；硬化 DOM 解析
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (_: Exception) {
            // 部分实现不支持全部 feature，尽量设置
        }
        return factory
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
                        if (fallbackManifest.isEmpty()) fallbackManifest = xml
                        if (!xml.contains("split=\"")) return@read xml
                    }
                }
            }
            fallbackManifest
        }
    }

    private fun ensureResourceNameIndex(): List<String> {
        resourceNameIndex?.let { return it }
        return rwLock.write {
            resourceNameIndex?.let { return@write it }
            checkEngineReady()
            val names = mutableListOf<String>()
            for (res in decompiler!!.resources) {
                // 仅索引路径名，不 loadContent
                val n = res.deobfName ?: res.originalName
                if (!n.isNullOrBlank()) names.add(n)
            }
            val sorted = names.filter { it.isNotEmpty() }.distinct().sorted()
            resourceNameIndex = sorted
            sorted
        }
    }

    fun getResourceFile(name: String): String {
        if (name.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "Missing 'file_name'", 400)
        }
        return rwLock.read {
            checkEngineReady()
            for (res in decompiler!!.resources) {
                val n = res.deobfName ?: res.originalName ?: continue
                if (n == name || n.endsWith("/$name")) {
                    val container = res.loadContent() ?: continue
                    if (container.text != null) {
                        return@read container.text.codeStr
                    }
                    throw DecompileException(
                        DecompileException.NOT_FOUND,
                        "Resource '$name' is binary or has no text content",
                        404
                    )
                }
                // 递归子容器仅在匹配前缀时展开
                if (name.contains("/") && (name.startsWith(n) || n.contains(name.substringBeforeLast('/')))) {
                    val container = res.loadContent()
                    if (container != null) {
                        val found = findInContainer(container, name)
                        if (found != null) return@read found
                    }
                }
            }
            // 兜底：尝试 load 全部 resources 的顶层匹配（仍不做全树常驻）
            for (res in decompiler!!.resources) {
                val container = res.loadContent() ?: continue
                val found = findInContainer(container, name)
                if (found != null) return@read found
            }
            throw DecompileException(DecompileException.NOT_FOUND, "Resource not found: $name", 404)
        }
    }

    private fun findInContainer(container: jadx.core.xmlgen.ResContainer, name: String): String? {
        val resName = container.fileName ?: container.name
        if (resName == name || resName?.endsWith("/$name") == true || container.name == name) {
            if (container.text != null) return container.text.codeStr
        }
        val subs = container.subFiles
        if (subs != null) {
            for (sub in subs) {
                val found = findInContainer(sub, name)
                if (found != null) return found
            }
        }
        return null
    }

    fun getAllResourceFileNames(offset: Int, count: Int): List<String> {
        val all = ensureResourceNameIndex()
        return pageList(all, offset, count)
    }

    fun getStrings(offset: Int, count: Int): List<Map<String, String>> {
        val stringsXml = try {
            getResourceFile("res/values/strings.xml")
        } catch (_: DecompileException) {
            return emptyList()
        }
        val strings = mutableListOf<Map<String, String>>()
        try {
            val factory = secureXmlFactory(namespaceAware = false)
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
            for (match in regex.findAll(stringsXml)) {
                strings.add(mapOf("name" to match.groupValues[1], "value" to match.groupValues[2]))
            }
        }
        return pageList(strings, offset, count)
    }

    private fun androidAttr(node: Element, localName: String): String {
        // WHY: namespaceAware=true 时 getAttribute("android:name") 在 JDK DOM 常为空
        val ns = node.getAttributeNS("http://schemas.android.com/apk/res/android", localName)
        if (ns.isNotEmpty()) return ns
        val prefixed = node.getAttribute("android:$localName")
        if (prefixed.isNotEmpty()) return prefixed
        return node.getAttribute(localName)
    }

    fun getMainActivity(): JavaClass? {
        val manifestXml = getManifest()
        if (manifestXml.isEmpty()) return null
        try {
            val factory = secureXmlFactory(namespaceAware = true)
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(ByteArrayInputStream(manifestXml.toByteArray(Charsets.UTF_8)))

            var targetClassName: String? = null
            for (tag in listOf("activity", "activity-alias")) {
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
                            if (androidAttr(action, "name") == "android.intent.action.MAIN") isMain = true
                        }
                        val categories = filter.getElementsByTagName("category")
                        for (k in 0 until categories.length) {
                            val category = categories.item(k) as Element
                            if (androidAttr(category, "name") == "android.intent.category.LAUNCHER") isLauncher = true
                        }
                    }
                    if (isMain && isLauncher) {
                        targetClassName = if (tag == "activity-alias") {
                            androidAttr(node, "targetActivity")
                        } else {
                            androidAttr(node, "name")
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
                return getClassByName(targetClassName)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse main activity from manifest: {}", e.message)
        }
        return null
    }

    /**
     * 搜索类。
     * WHY: search_in=code 默认全包反编译是性能炸弹；method/field/comment 从未实现却写在文档里。
     * DECISION: 仅 class|code；默认由 HTTP 层给 class；code 模式硬限额 + 请求 timeout 上限。
     * 返回不含全文 code，避免撑爆 Agent 上下文。
     */
    fun searchClassesByKeyword(
        term: String,
        pkg: String,
        searchIn: String,
        offset: Int,
        count: Int,
        timeoutSeconds: Long? = null,
        maxScan: Int? = null,
        maxDecompile: Int? = null
    ): Map<String, Any> {
        val maxScanClasses = (maxScan?.takeIf { it > 0 }) ?: defaultMaxScan
        val maxDecompileOnSearch = (maxDecompile?.takeIf { it > 0 }) ?: defaultMaxDecompileOnSearch
        if (term.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "search_term must not be empty", 400)
        }
        val scopes = searchIn.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val allowed = setOf("class", "code")
        val unknown = scopes.filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw DecompileException(
                DecompileException.INVALID_ARGUMENT,
                "Unsupported search_in value(s): ${unknown.joinToString(",")}. Allowed: class, code",
                400
            )
        }
        if (scopes.isEmpty()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "search_in must include class and/or code", 400)
        }
        val searchCode = scopes.contains("code")
        val searchClass = scopes.contains("class")
        val budgetSec = min(effectiveTimeout(timeoutSeconds), searchTimeoutCapSec)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(budgetSec)

        val targetClasses = rwLock.read {
            checkEngineReady()
            if (pkg.isNotEmpty()) {
                val prefix = if (pkg.endsWith(".")) pkg else "$pkg."
                classCache.values.filter { it.fullName == pkg || it.fullName.startsWith(prefix) || it.fullName.startsWith(pkg) }
            } else {
                classCache.values.toList()
            }
        }

        val results = mutableListOf<Map<String, String>>()
        var scanned = 0
        var decompiled = 0
        var budgetHit = false

        for (javaClass in targetClasses) {
            if (scanned >= maxScanClasses) {
                budgetHit = true
                break
            }
            if (System.nanoTime() > deadline) {
                budgetHit = true
                break
            }
            scanned++

            var matched = false
            var matchType = ""
            if (searchClass && javaClass.fullName.contains(term, ignoreCase = true)) {
                matched = true
                matchType = "class"
            }
            if (!matched && searchCode) {
                if (decompiled >= maxDecompileOnSearch) {
                    budgetHit = true
                    break
                }
                try {
                    val remainingSec = max(1L, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()))
                    val source = getClassSource(javaClass, remainingSec)
                    decompiled++
                    if (source.contains(term, ignoreCase = true)) {
                        matched = true
                        matchType = "code"
                    }
                } catch (_: DecompileException) {
                    // 单类超时/失败跳过，不中断整次搜索
                    decompiled++
                }
            }
            if (matched) {
                results.add(mapOf("class_name" to javaClass.fullName, "match_type" to matchType))
            }
        }

        val page = pageList(results, offset, count)
        return mapOf(
            "classes" to page,
            "scanned" to scanned,
            "decompiled" to decompiled,
            "matched_total" to results.size,
            "budget_hit" to budgetHit,
            "limits" to mapOf(
                "max_scan" to maxScanClasses,
                "max_decompile" to maxDecompileOnSearch,
                "timeout_sec" to budgetSec
            )
        )
    }

    fun searchMethodByName(methodName: String, offset: Int = 0, count: Int = 50): List<Map<String, String>> {
        if (methodName.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "method_name must not be empty", 400)
        }
        return rwLock.read {
            checkEngineReady()
            val results = mutableListOf<Map<String, String>>()
            var scanned = 0
            for (javaClass in classCache.values) {
                if (scanned >= defaultMaxScan) break
                scanned++
                for (method in javaClass.methods) {
                    if (method.name.contains(methodName, ignoreCase = true)) {
                        results.add(
                            mapOf(
                                "class_name" to javaClass.fullName,
                                "method" to method.name
                            )
                        )
                    }
                }
            }
            pageList(results, offset, count)
        }
    }

    fun getXrefsToClass(className: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val simpleName = javaClass.name
        val usage = rwLock.read { javaClass.useIn }
        val page = pageList(usage, offset, min(count, 50).coerceAtLeast(1))
        return page.map { node ->
            val parentClass = node.topParentClass
            val snippet = extractSnippetFromParent(parentClass, timeoutSeconds, simpleName, className)
            mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
        }
    }

    fun getXrefsToMethod(className: String, methodName: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val targetMethod = rwLock.read {
            javaClass.methods.find { it.name == methodName }
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Method '$methodName' not found in class '$className'", 404)
        }
        val usage = rwLock.read { targetMethod.useIn }
        val page = pageList(usage, offset, min(count, 50).coerceAtLeast(1))
        return page.map { node ->
            val parentClass = node.topParentClass
            val snippet = extractSnippetFromParent(parentClass, timeoutSeconds, methodName, targetMethod.name)
            mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
        }
    }

    fun getXrefsToField(className: String, fieldName: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val targetField = rwLock.read {
            javaClass.fields.find { it.name == fieldName }
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Field '$fieldName' not found in class '$className'", 404)
        }
        val usage = rwLock.read { targetField.useIn }
        val page = pageList(usage, offset, min(count, 50).coerceAtLeast(1))
        return page.map { node ->
            val parentClass = node.topParentClass
            val snippet = extractSnippetFromParent(parentClass, timeoutSeconds, fieldName, targetField.name)
            mapOf("class_name" to parentClass.fullName, "code_snippet" to snippet)
        }
    }

    private fun extractSnippetFromParent(parentClass: JavaClass, timeoutSeconds: Long?, vararg targetKeywords: String): String {
        return try {
            val source = getClassSource(parentClass, timeoutSeconds ?: 5L)
            val lines = source.lines()
            for ((idx, line) in lines.withIndex()) {
                for (kw in targetKeywords) {
                    if (kw.isNotEmpty() && line.contains(kw)) {
                        return "Line ${idx + 1}: ${line.trim()}"
                    }
                }
            }
            "Referenced in ${parentClass.fullName}"
        } catch (_: Exception) {
            "Referenced in ${parentClass.fullName} (snippet unavailable)"
        }
    }

    fun requireClass(className: String?): JavaClass {
        requireNotNull(className) { "Missing required parameter 'class_name'" }
        return getClassByName(className)
            ?: throw DecompileException(DecompileException.NOT_FOUND, "Class not found: $className", 404)
    }

    private fun <T> pageList(list: List<T>, offset: Int, count: Int): List<T> {
        if (offset >= list.size) return emptyList()
        val toIndex = if (count > 0) minOf(offset + count, list.size) else list.size
        return list.subList(offset, toIndex)
    }
}
