package com.yyang.jadx_server.service

import com.google.gson.Gson
import com.yyang.jadx_server.cache.JadxCacheManager
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.plugins.input.insns.InsnIndexType
import jadx.core.dex.nodes.MethodNode
import jadx.core.utils.android.AndroidResourcesMap
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
    /** orig / 无包名 / p000↔defpackage 别名 → 同一 JavaClass；不用于列表，避免重复 */
    private val classAliasIndex = ConcurrentHashMap<String, JavaClass>()

    // WHY: 只缓存资源「名字列表」，内容按需 loadContent，避免大包一次灌满堆（R1）
    @Volatile private var resourceNameIndex: List<String>? = null

    /** DEX const-string 索引；首次 scope=string 时构建，完整后写入磁盘 */
    @Volatile private var dexStringIndex: List<Map<String, String>>? = null
    private val gson = Gson()

    companion object {
        const val MAX_AGENT_CODE_CHARS = 80_000
        private const val PREVIEW_CHARS = 200
    }

    var currentApkPath: String? = null
        private set

    var classesCount: Int = 0
        private set

    /** load 时是否打开 JADX deobf（短名 → p000 / mo1360kO，对齐 GUI） */
    var deobfuscationOn: Boolean = true
        private set

    var cacheManager: JadxCacheManager? = null
        private set

    /** 解析后的主输入文件（用于 clear_cache 路径对齐） */
    var mainInputFile: File? = null
        private set

    val defaultTimeoutSeconds: Long = System.getProperty("jadx.decompile.timeout")?.toLongOrNull()
        ?: System.getenv("JADX_DECOMPILE_TIMEOUT")?.toLongOrNull()
        ?: 20L

    // WHY: code/comment 才需要预算；class/method/field 只扫名字，6 万类也是毫秒级
    // DECISION: 元数据搜索默认全量；code 默认 max_scan=500 / max_decompile=50，请求可覆盖
    private val defaultMaxScan = 500
    private val defaultMaxDecompileOnSearch = 50
    private val searchTimeoutCapSec = 600L

    fun loadApk(apkPath: String, deobfuscationOn: Boolean = true) {
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
                // WHY: GUI 默认 deobf + minLength=3，才会把 kO/a 变成 mo1360kO/f230385a，空包变成 p000
                // DECISION: 默认开，长度阈值对齐 jadx-gui JadxSettings；apk_load.deobf=false 可关
                args.isDeobfuscationOn = deobfuscationOn
                this.deobfuscationOn = deobfuscationOn
                if (deobfuscationOn) {
                    args.deobfuscationMinLength = 3
                    args.deobfuscationMaxLength = 64
                }
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
                    indexClass(javaClass)
                }

                this.decompiler = newDecompiler
                this.cacheManager = cm
                this.currentApkPath = apkPath
                resourceNameIndex = null
                applyPersistedRenamesLocked()
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
        classAliasIndex.clear()
        resourceNameIndex = null
        dexStringIndex = null
        classesCount = 0
        deobfuscationOn = true
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

    fun clipForAgent(code: String, maxChars: Int = MAX_AGENT_CODE_CHARS): Map<String, Any> {
        if (code.length <= maxChars) {
            return mapOf("code" to code, "truncated" to false, "total_chars" to code.length)
        }
        return mapOf(
            "code" to code.substring(0, maxChars),
            "truncated" to true,
            "total_chars" to code.length,
            "hint" to "Truncated at $maxChars chars. Prefer decompile target=method, or request a smaller class."
        )
    }

    fun packageOverview(limit: Int = 20): Map<String, Any> {
        val names = rwLock.read {
            checkEngineReady()
            classCache.keys().toList()
        }
        val counts = HashMap<String, Int>()
        for (name in names) {
            val pkg = name.substringBeforeLast('.', "")
            val bucket = when {
                pkg.isEmpty() -> "(default)"
                pkg == "p000" || pkg.startsWith("p000.") -> "p000"
                pkg == "defpackage" -> "defpackage"
                else -> pkg.split('.').take(2).joinToString(".")
            }
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        val topPackages = counts.entries.sortedByDescending { it.value }.take(limit).map {
            mapOf("package" to it.key, "classes" to it.value)
        }
        return mapOf("topPackages" to topPackages)
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
        val resolved = resolveMethod(javaClass, methodName)
        val fullSource = getClassSource(javaClass, timeoutSeconds)
        val names = listOfNotNull(resolved?.name, methodName).distinct()
        val snippet = names.firstNotNullOfOrNull { extractMethodSnippetFromClassSource(fullSource, it) }
            ?: throw DecompileException(
                DecompileException.NOT_FOUND,
                "Method '$methodName' not found in decompiled source of ${javaClass.fullName}",
                404
            )
        return snippet
    }

    private fun resolveMethod(javaClass: JavaClass, methodName: String): JavaMethod? {
        val exact = javaClass.methods.filter { it.name == methodName }
        if (exact.size == 1) return exact[0]
        if (exact.isNotEmpty()) return exact[0]
        val ended = javaClass.methods.filter { it.name.endsWith(methodName) && it.name != methodName }
        return ended.singleOrNull()
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

    private fun indexClass(javaClass: JavaClass) {
        classCache[javaClass.fullName] = javaClass
        fun alias(key: String) {
            if (key.isNotBlank()) classAliasIndex.putIfAbsent(key, javaClass)
        }
        val raw = javaClass.rawName
        val pkg = javaClass.getPackage()
        alias(raw)
        if (pkg.isNotBlank() && raw.isNotBlank() && !raw.contains('.')) alias("$pkg.$raw")
        when {
            javaClass.fullName.startsWith("p000.") -> alias("defpackage." + javaClass.fullName.removePrefix("p000."))
            javaClass.fullName.startsWith("defpackage.") -> alias("p000." + javaClass.fullName.removePrefix("defpackage."))
        }
    }

    fun getClassByName(className: String): JavaClass? {
        val raw = className.trim()
        if (raw.isEmpty()) return null
        val candidates = linkedSetOf(raw)
        when {
            raw.startsWith("p000.") -> {
                candidates += raw.removePrefix("p000.")
                candidates += "defpackage." + raw.removePrefix("p000.")
            }
            raw.startsWith("defpackage.") -> {
                candidates += raw.removePrefix("defpackage.")
                candidates += "p000." + raw.removePrefix("defpackage.")
            }
            !raw.contains('.') -> {
                candidates += "p000.$raw"
                candidates += "defpackage.$raw"
            }
        }
        return rwLock.read {
            for (c in candidates) {
                classCache[c]?.let { return@read it }
                classAliasIndex[c]?.let { return@read it }
                decompiler?.searchJavaClassByOrigFullName(c)?.let { return@read it }
                decompiler?.searchJavaClassByAliasFullName(c)?.let { return@read it }
            }
            val simple = raw.substringAfterLast('.')
            val hits = classCache.values.filter { it.name == simple || it.rawName == simple }.distinct()
            if (hits.size == 1) hits[0] else null
        }
    }

    fun methodEntries(javaClass: JavaClass): List<Map<String, String>> =
        javaClass.methods.map { m ->
            mapOf(
                "name" to m.name,
                "full_name" to m.fullName,
                "access" to m.accessFlags.toString()
            )
        }

    fun fieldEntries(javaClass: JavaClass): List<Map<String, String>> =
        javaClass.fields.map { f ->
            mapOf(
                "name" to f.name,
                "full_name" to f.fullName,
                "type" to f.type.toString(),
                "access" to f.accessFlags.toString()
            )
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
                    val container = safeLoadContent(res) ?: continue
                    if (container.text != null) {
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
                    val container = safeLoadContent(res) ?: continue
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
                    val container = safeLoadContent(res)
                    if (container != null) {
                        val found = findInContainer(container, name)
                        if (found != null) return@read found
                    }
                }
            }
            // 兜底：尝试 load 全部 resources 的顶层匹配（仍不做全树常驻）
            for (res in decompiler!!.resources) {
                val container = safeLoadContent(res) ?: continue
                val found = findInContainer(container, name)
                if (found != null) return@read found
            }
            throw DecompileException(DecompileException.NOT_FOUND, "Resource not found: $name", 404)
        }
    }

    private fun safeLoadContent(res: jadx.api.ResourceFile): jadx.core.xmlgen.ResContainer? {
        return try {
            res.loadContent()
        } catch (e: Exception) {
            log.warn("loadContent failed for {}: {}", res.originalName ?: res.deobfName, e.message)
            null
        }
    }

    private fun findInContainer(container: jadx.core.xmlgen.ResContainer, name: String): String? {
        return try {
            val resName = container.fileName ?: container.name
            if (resName == name || resName?.endsWith("/$name") == true || container.name == name) {
                if (container.text != null) return container.text.codeStr
            }
            val subs = container.subFiles ?: return null
            for (sub in subs) {
                val found = findInContainer(sub, name)
                if (found != null) return found
            }
            null
        } catch (e: Exception) {
            log.warn("findInContainer skipped for {}: {}", name, e.message)
            null
        }
    }

    fun getAllResourceFileNames(offset: Int, count: Int): List<String> {
        val all = ensureResourceNameIndex()
        return pageList(all, offset, count)
    }

    fun countResources(): Int = ensureResourceNameIndex().size

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

    fun getManifestPackage(): String {
        val xml = getManifest()
        if (xml.isEmpty()) return ""
        return try {
            val factory = secureXmlFactory(namespaceAware = true)
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            (document.getElementsByTagName("manifest").item(0) as? Element)?.getAttribute("package") ?: ""
        } catch (e: Exception) {
            log.warn("Failed to parse manifest package: {}", e.message)
            ""
        }
    }

    /**
     * 列出 Manifest 组件，对齐 GUI get_manifest_component。
     * componentType: activity|service|receiver|provider（activity 含 activity-alias）
     */
    fun getManifestComponents(componentType: String, onlyExported: Boolean = false): Map<String, Any> {
        val type = componentType.trim().lowercase()
        val allowed = mapOf(
            "activity" to listOf("activity", "activity-alias"),
            "service" to listOf("service"),
            "receiver" to listOf("receiver"),
            "provider" to listOf("provider")
        )
        val tags = allowed[type]
            ?: throw DecompileException(
                DecompileException.INVALID_ARGUMENT,
                "component_type must be activity|service|receiver|provider",
                400
            )
        val xml = getManifest()
        if (xml.isEmpty()) {
            return mapOf("component_type" to type, "only_exported" to onlyExported, "count" to 0, "components" to emptyList<Map<String, String>>())
        }
        val pkg = getManifestPackage()
        val components = mutableListOf<Map<String, String>>()
        try {
            val factory = secureXmlFactory(namespaceAware = true)
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            for (tag in tags) {
                val nodes = document.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    val rawName = if (tag == "activity-alias") androidAttr(node, "targetActivity").ifBlank { androidAttr(node, "name") } else androidAttr(node, "name")
                    val name = qualifyComponentName(rawName, pkg)
                    val exportedAttr = androidAttr(node, "exported")
                    val hasFilter = node.getElementsByTagName("intent-filter").length > 0
                    val exported = when (exportedAttr.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> hasFilter
                    }
                    if (onlyExported && !exported) continue
                    components.add(
                        mapOf(
                            "name" to name,
                            "tag" to tag,
                            "exported" to exported.toString(),
                            "enabled" to androidAttr(node, "enabled").ifBlank { "true" }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse manifest components: {}", e.message)
        }
        return mapOf(
            "component_type" to type,
            "only_exported" to onlyExported,
            "count" to components.size,
            "components" to components
        )
    }

    private fun qualifyComponentName(name: String, pkg: String): String {
        if (name.isBlank()) return name
        if (name.startsWith(".")) return pkg + name
        if (!name.contains(".")) return if (pkg.isBlank()) name else "$pkg.$name"
        return name
    }

    /**
     * 搜索类。
     * WHY: search_in=code 默认全包反编译是性能炸弹。
     * DECISION: class/method/field 全量扫名字；code/comment 硬限额 + 请求 timeout。
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
            .map { if (it == "method_name") "method" else it }
        val allowed = setOf("class", "code", "method", "field", "comment", "string")
        val unknown = scopes.filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw DecompileException(
                DecompileException.INVALID_ARGUMENT,
                "Unsupported search_in value(s): ${unknown.joinToString(",")}. Allowed: class, code, method, field, comment, string",
                400
            )
        }
        if (scopes.isEmpty()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "search_in must include class, code, method, field, comment and/or string", 400)
        }
        if (scopes.contains("string") && scopes.size == 1) {
            return searchDexStrings(term, pkg, offset, count, timeoutSeconds, maxScan)
        }
        val searchCode = scopes.contains("code")
        val searchComment = scopes.contains("comment")
        val searchClass = scopes.contains("class")
        val searchMethod = scopes.contains("method")
        val searchField = scopes.contains("field")
        val needsDecompile = searchCode || searchComment
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
            if (needsDecompile && scanned >= maxScanClasses) {
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
            var preview = ""
            if (searchClass && (javaClass.fullName.contains(term, ignoreCase = true) || javaClass.rawName.contains(term, ignoreCase = true))) {
                matched = true
                matchType = "class"
                preview = javaClass.fullName
            }
            if (!matched && searchMethod) {
                val m = javaClass.methods.firstOrNull { it.name.contains(term, ignoreCase = true) }
                if (m != null) {
                    matched = true
                    matchType = "method"
                    preview = m.fullName
                }
            }
            if (!matched && searchField) {
                val f = javaClass.fields.firstOrNull { it.name.contains(term, ignoreCase = true) }
                if (f != null) {
                    matched = true
                    matchType = "field"
                    preview = f.fullName
                }
            }
            if (!matched && needsDecompile) {
                if (decompiled >= maxDecompileOnSearch) {
                    budgetHit = true
                    break
                }
                try {
                    val remainingSec = max(1L, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()))
                    val source = getClassSource(javaClass, remainingSec)
                    decompiled++
                    if (searchCode && source.contains(term, ignoreCase = true)) {
                        matched = true
                        matchType = "code"
                        preview = source.lines().firstOrNull { it.contains(term, ignoreCase = true) }?.trim().orEmpty()
                    } else if (searchComment && source.lines().any { isCommentLine(it) && it.contains(term, ignoreCase = true) }) {
                        matched = true
                        matchType = "comment"
                        preview = source.lines().firstOrNull { isCommentLine(it) && it.contains(term, ignoreCase = true) }?.trim().orEmpty()
                    }
                } catch (_: DecompileException) {
                    // 单类超时/失败跳过，不中断整次搜索
                    decompiled++
                }
            }
            if (matched) {
                results.add(
                    mapOf(
                        "class_name" to javaClass.fullName,
                        "match_type" to matchType,
                        "preview" to preview.take(PREVIEW_CHARS)
                    )
                )
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

    fun searchMethodByName(
        methodName: String,
        offset: Int = 0,
        count: Int = 50,
        pkg: String = "",
        maxScan: Int? = null
    ): Map<String, Any> {
        if (methodName.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "method_name must not be empty", 400)
        }
        return rwLock.read {
            checkEngineReady()
            val results = mutableListOf<Map<String, String>>()
            var scanned = 0
            val cap = maxScan?.takeIf { it > 0 } ?: Int.MAX_VALUE
            val classes = if (pkg.isNotEmpty()) {
                val prefix = if (pkg.endsWith(".")) pkg else "$pkg."
                classCache.values.filter { it.fullName == pkg || it.fullName.startsWith(prefix) }
            } else {
                classCache.values
            }
            for (javaClass in classes) {
                if (scanned >= cap) break
                scanned++
                for (method in javaClass.methods) {
                    if (method.name.contains(methodName, ignoreCase = true)) {
                        results.add(
                            mapOf(
                                "class_name" to javaClass.fullName,
                                "method" to method.name,
                                "preview" to method.fullName.take(PREVIEW_CHARS)
                            )
                        )
                    }
                }
            }
            mapOf(
                "matches" to pageList(results, offset, count),
                "matched_total" to results.size,
                "scanned" to scanned,
                "budget_hit" to (scanned >= cap && cap != Int.MAX_VALUE)
            )
        }
    }

    fun searchFieldByName(
        fieldName: String,
        offset: Int = 0,
        count: Int = 50,
        pkg: String = ""
    ): Map<String, Any> {
        if (fieldName.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "search_term must not be empty", 400)
        }
        return rwLock.read {
            checkEngineReady()
            val results = mutableListOf<Map<String, String>>()
            val classes = if (pkg.isNotEmpty()) {
                val prefix = if (pkg.endsWith(".")) pkg else "$pkg."
                classCache.values.filter { it.fullName == pkg || it.fullName.startsWith(prefix) }
            } else {
                classCache.values
            }
            for (javaClass in classes) {
                for (field in javaClass.fields) {
                    if (field.name.contains(fieldName, ignoreCase = true)) {
                        results.add(
                            mapOf(
                                "class_name" to javaClass.fullName,
                                "field" to field.name,
                                "preview" to field.fullName.take(PREVIEW_CHARS)
                            )
                        )
                    }
                }
            }
            mapOf("matches" to pageList(results, offset, count), "matched_total" to results.size)
        }
    }

    fun getXrefsToClass(className: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val usage = rwLock.read { javaClass.useIn }
        val page = pageList(usage, offset, min(count, 50).coerceAtLeast(1))
        return page.map { usageEntry(it, timeoutSeconds, javaClass.name, javaClass.fullName, javaClass.rawName) }
    }

    fun getXrefsToMethod(className: String, methodName: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val targetMethod = rwLock.read {
            resolveMethod(javaClass, methodName)
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Method '$methodName' not found in class '$className'", 404)
        }
        val callers = rwLock.read { collectMethodCallers(targetMethod) }
        val page = pageList(callers, offset, min(count, 50).coerceAtLeast(1))
        return page.map { mth ->
            val row = mutableMapOf(
                "class_name" to mth.declaringClass.fullName,
                "method" to mth.alias
            )
            try {
                val parent = getClassByName(mth.declaringClass.fullName)
                if (parent != null) {
                    val snippet = extractSnippetFromParent(parent, timeoutSeconds, mth.alias, methodName, targetMethod.name)
                    if (snippet.isNotEmpty()) row["code_snippet"] = snippet
                }
            } catch (_: Exception) {
            }
            row
        }
    }

    private fun collectMethodCallers(target: JavaMethod): List<MethodNode> {
        val out = LinkedHashSet<MethodNode>()
        val node = target.methodNode
        out.addAll(node.useIn)
        try {
            for (rel in target.overrideRelatedMethods) {
                out.addAll(rel.methodNode.useIn)
            }
        } catch (_: Exception) {
        }
        val origName = try {
            node.methodInfo.name
        } catch (_: Exception) {
            node.name
        }
        try {
            val root = decompiler?.root
            node.declaringClass.visitSuperTypes { _, type ->
                val superCls = root?.resolveClass(type) ?: return@visitSuperTypes
                val related = superCls.searchMethodByShortName(origName) ?: superCls.searchMethodByShortName(node.alias)
                if (related != null) out.addAll(related.useIn)
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            val root = decompiler?.root
            if (root != null) {
                for (cls in root.classes) {
                    for (mth in cls.methods) {
                        try {
                            if (mth.used.contains(node)) out.add(mth)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        out.remove(node)
        return out.toList()
    }

    fun getXrefsToField(className: String, fieldName: String, offset: Int, count: Int, timeoutSeconds: Long? = null): List<Map<String, String>> {
        val javaClass = requireClass(className)
        val targetField = rwLock.read {
            javaClass.fields.find { it.name == fieldName || it.rawName == fieldName }
                ?: throw DecompileException(DecompileException.NOT_FOUND, "Field '$fieldName' not found in class '$className'", 404)
        }
        val usage = rwLock.read { targetField.useIn }
        val page = pageList(usage, offset, min(count, 50).coerceAtLeast(1))
        return page.map { usageEntry(it, timeoutSeconds, fieldName, targetField.name) }
    }

    private fun usageEntry(node: JavaNode, timeoutSeconds: Long?, vararg keywords: String): Map<String, String> {
        val parentClass = node.topParentClass
        val methodName = if (node is JavaMethod) node.name else ""
        val fieldName = if (node is JavaField) node.name else ""
        val snippet = extractSnippetFromParent(parentClass, timeoutSeconds, methodName, *keywords)
        val out = mutableMapOf("class_name" to parentClass.fullName)
        if (methodName.isNotEmpty()) out["method"] = methodName
        if (fieldName.isNotEmpty()) out["field"] = fieldName
        if (snippet.isNotEmpty()) out["code_snippet"] = snippet
        if (node is JavaMethod) {
            try {
                val methodSrc = getMethodSourceCode(parentClass, node.name, min(timeoutSeconds ?: 5L, 8L))
                if (methodSrc.length in 1..4000) out["method_code"] = methodSrc
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun extractSnippetFromParent(parentClass: JavaClass, timeoutSeconds: Long?, @Suppress("UNUSED_PARAMETER") enclosingMethod: String, vararg targetKeywords: String): String {
        return try {
            val perClass = min(timeoutSeconds ?: 5L, 5L)
            val source = getClassSource(parentClass, perClass)
            val lines = source.lines()
            val ranked = targetKeywords.filter { it.isNotEmpty() }.sortedByDescending { it.length }
            var fallback: String? = null
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
                for (kw in ranked) {
                    if (!identifierHit(trimmed, kw)) continue
                    if (isDeclarationLine(trimmed, kw)) {
                        if (fallback == null) fallback = "Line ${idx + 1}: $trimmed"
                        continue
                    }
                    return "Line ${idx + 1}: $trimmed"
                }
            }
            fallback ?: "Referenced in ${parentClass.fullName}"
        } catch (_: Exception) {
            "Referenced in ${parentClass.fullName} (snippet unavailable)"
        }
    }

    private fun isDeclarationLine(line: String, kw: String): Boolean {
        val t = line.trim()
        if (t.startsWith("/*") || t.startsWith("*") || t.startsWith("//")) return true
        return Regex("""(?:final|private|protected|public|static|volatile|transient|\s)+.+\s+\Q$kw\E\s*[;=]""").containsMatchIn(t)
    }

    private fun identifierHit(line: String, kw: String): Boolean {
        if (kw.length >= 3) return line.contains(kw)
        val regex = Regex("(?<![A-Za-z0-9_])${Regex.escape(kw)}(?![A-Za-z0-9_])")
        return regex.containsMatchIn(line)
    }

    private fun isCommentLine(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")
    }

    fun searchDexStrings(
        term: String,
        pkg: String = "",
        offset: Int = 0,
        count: Int = 50,
        timeoutSeconds: Long? = null,
        maxScan: Int? = null
    ): Map<String, Any> {
        if (term.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "search_term must not be empty", 400)
        }
        val budgetSec = min(effectiveTimeout(timeoutSeconds), searchTimeoutCapSec)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(budgetSec)
        val maxScanClasses = (maxScan?.takeIf { it > 0 }) ?: Int.MAX_VALUE
        val prefix = if (pkg.isNotEmpty() && !pkg.endsWith(".")) "$pkg." else pkg

        fun persist(store: DexStringIndexStore) {
            try {
                cacheManager?.saveStringIndexJson(deobfuscationOn, gson.toJson(store))
            } catch (e: Exception) {
                log.warn("Failed to persist string index: {}", e.message)
            }
        }

        val mem = dexStringIndex
        val disk: DexStringIndexStore? = if (mem != null) {
            DexStringIndexStore(complete = true, scanned = mem.size, total = mem.size, hits = mem)
        } else {
            try {
                cacheManager?.loadStringIndexJson(deobfuscationOn)?.let {
                    gson.fromJson(it, DexStringIndexStore::class.java)
                }
            } catch (_: Exception) {
                null
            }
        }

        val index: List<Map<String, String>>
        var fromCache = false
        var budgetHit = false
        if (disk?.complete == true) {
            dexStringIndex = disk.hits
            index = disk.hits
            fromCache = true
        } else {
            val root = rwLock.read {
                checkEngineReady()
                decompiler!!.root
            }
            val classes = root.classes
            val built = ArrayList<Map<String, String>>(disk?.hits?.size ?: 4096)
            if (disk != null) built.addAll(disk.hits)
            var scanned = disk?.scanned ?: 0
            if (scanned > classes.size) scanned = 0
            val start = scanned
            for (i in start until classes.size) {
                if (System.nanoTime() > deadline || (i - start) >= maxScanClasses) {
                    budgetHit = true
                    break
                }
                scanned = i + 1
                val cls = classes[i]
                val clsName = cls.fullName
                for (mth in cls.methods) {
                    if (System.nanoTime() > deadline) {
                        budgetHit = true
                        break
                    }
                    if (mth.isNoCode) continue
                    try {
                        val reader = mth.codeReader ?: continue
                        reader.visitInstructions { insn ->
                            if (insn.indexType != InsnIndexType.STRING_REF) return@visitInstructions
                            try {
                                insn.decode()
                                val value = insn.indexAsString ?: return@visitInstructions
                                if (value.isEmpty()) return@visitInstructions
                                built.add(
                                    mapOf(
                                        "class_name" to clsName,
                                        "method" to mth.alias,
                                        "preview" to value.take(PREVIEW_CHARS),
                                        "match_type" to "string"
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            val complete = scanned >= classes.size && !budgetHit
            if (complete) {
                dexStringIndex = built
            }
            persist(DexStringIndexStore(complete = complete, scanned = scanned, total = classes.size, hits = built))
            index = built
        }

        val matched = index.filter { hit ->
            val inPkg = prefix.isEmpty() || hit.getValue("class_name") == pkg || hit.getValue("class_name").startsWith(prefix)
            inPkg && hit.getValue("preview").contains(term, ignoreCase = true)
        }
        return mapOf(
            "classes" to pageList(matched, offset, count),
            "matched_total" to matched.size,
            "indexed" to index.size,
            "from_cache" to fromCache,
            "budget_hit" to (dexStringIndex == null),
            "limits" to mapOf("timeout_sec" to budgetSec, "max_scan" to maxScanClasses)
        )
    }

    fun lookupResourceId(idRaw: String): Map<String, Any> {
        if (idRaw.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "Missing resource id", 400)
        }
        rwLock.read { checkEngineReady() }
        val trimmed = idRaw.trim()
        val id = when {
            trimmed.startsWith("0x") || trimmed.startsWith("0X") -> trimmed.substring(2).toLongOrNull(16)?.toInt()
            else -> trimmed.toLongOrNull()?.toInt() ?: trimmed.toLongOrNull(16)?.toInt()
        } ?: throw DecompileException(DecompileException.INVALID_ARGUMENT, "Invalid resource id: $idRaw", 400)

        val names = try {
            decompiler!!.root.constValues.resourcesNames
        } catch (_: Exception) {
            emptyMap<Int, String>()
        }
        val mapped = names[id] ?: AndroidResourcesMap.getResName(id) ?: ""
        var stringValue = ""
        if (mapped.isNotEmpty()) {
            val shortName = mapped.substringAfterLast('/')
            try {
                val strings = getStrings(0, 5000)
                val hit = strings.firstOrNull { it["name"] == shortName || it["name"] == mapped }
                if (hit != null) stringValue = hit["value"].orEmpty()
            } catch (_: Exception) {
            }
        }
        return mapOf(
            "id" to id,
            "id_hex" to ("0x" + Integer.toHexString(id)),
            "name" to mapped,
            "value" to stringValue
        )
    }

    fun renameSymbol(targetType: String, className: String, name: String, newName: String): Map<String, Any> {
        if (newName.isBlank()) {
            throw DecompileException(DecompileException.INVALID_ARGUMENT, "new_name must not be empty", 400)
        }
        val javaClass = requireClass(className)
        val oldClassName = javaClass.fullName
        when (targetType.lowercase()) {
            "class" -> javaClass.classNode.rename(newName)
            "method" -> {
                val m = resolveMethod(javaClass, name)
                    ?: throw DecompileException(DecompileException.NOT_FOUND, "Method '$name' not found in '$className'", 404)
                m.methodNode.rename(newName)
            }
            "field" -> {
                val f = javaClass.fields.find { it.name == name || it.rawName == name }
                    ?: throw DecompileException(DecompileException.NOT_FOUND, "Field '$name' not found in '$className'", 404)
                f.fieldNode.rename(newName)
            }
            else -> throw DecompileException(
                DecompileException.INVALID_ARGUMENT,
                "target_type must be class|method|field",
                400
            )
        }
        try {
            javaClass.classNode.reloadCode()
        } catch (_: Exception) {
        }
        try {
            decompiler?.reloadCodeData()
        } catch (_: Exception) {
        }
        rwLock.write {
            cacheManager?.evict(oldClassName)
            cacheManager?.evict(javaClass.fullName)
            classCache.remove(oldClassName)
            indexClass(javaClass)
        }
        persistUserRename(UserRenameOp(targetType.lowercase(), oldClassName, name, newName))
        return mapOf(
            "status" to "success",
            "target_type" to targetType,
            "class_name" to javaClass.fullName,
            "old_class_name" to oldClassName,
            "name" to name,
            "new_name" to newName,
            "persisted" to true
        )
    }

    private fun applyPersistedRenamesLocked() {
        val json = try {
            cacheManager?.loadUserRenamesJson(deobfuscationOn)
        } catch (_: Exception) {
            null
        } ?: return
        val ops = try {
            gson.fromJson(json, Array<UserRenameOp>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            log.warn("Failed to parse persisted renames: {}", e.message)
            return
        }
        for (op in ops) {
            try {
                applyRenameOpLocked(op)
            } catch (e: Exception) {
                log.warn("Failed to apply rename {} {} -> {}: {}", op.type, op.name, op.newName, e.message)
            }
        }
        if (ops.isNotEmpty()) {
            log.info("Applied {} persisted rename(s)", ops.size)
        }
    }

    private fun applyRenameOpLocked(op: UserRenameOp) {
        val javaClass = getClassByName(op.className) ?: return
        val old = javaClass.fullName
        when (op.type) {
            "class" -> javaClass.classNode.rename(op.newName)
            "method" -> resolveMethod(javaClass, op.name)?.methodNode?.rename(op.newName)
            "field" -> javaClass.fields.find { it.name == op.name || it.rawName == op.name }?.fieldNode?.rename(op.newName)
        }
        try {
            javaClass.classNode.reloadCode()
        } catch (_: Exception) {
        }
        classCache.remove(old)
        indexClass(javaClass)
    }

    private fun persistUserRename(op: UserRenameOp) {
        try {
            val existing = cacheManager?.loadUserRenamesJson(deobfuscationOn)?.let {
                gson.fromJson(it, Array<UserRenameOp>::class.java)?.toMutableList()
            } ?: mutableListOf()
            existing.add(op)
            cacheManager?.saveUserRenamesJson(deobfuscationOn, gson.toJson(existing))
        } catch (e: Exception) {
            log.warn("Failed to persist rename: {}", e.message)
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

internal data class DexStringIndexStore(
    val complete: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val hits: List<Map<String, String>> = emptyList()
)

internal data class UserRenameOp(
    val type: String = "",
    val className: String = "",
    val name: String = "",
    val newName: String = ""
)
