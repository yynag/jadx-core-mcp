package com.yyang.jadx_server.server

import com.yyang.jadx_server.cache.JadxCacheManager
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WHY: 一 APK 一 JVM 对抗 jadx 高内存；卸载靠杀进程。
 * DECISION: 不限制 Worker 数量；load 失败（尤其 OOM）时提示 Agent 用 apk_unload 清理；Worker 仅绑 127.0.0.1。
 */
data class WorkerInstance(
    val apkId: String,
    val apkPath: String,
    val port: Int,
    val process: Process,
    @Volatile var isReady: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
)

class JadxProcessManager {
    private val log = LoggerFactory.getLogger(JadxProcessManager::class.java)
    private val lock = ReentrantLock()
    private val workers = ConcurrentHashMap<String, WorkerInstance>()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    @Volatile private var shutdownHookRegistered = false

    init {
        ensureShutdownHook()
    }

    private fun ensureShutdownHook() {
        if (shutdownHookRegistered) return
        synchronized(this) {
            if (shutdownHookRegistered) return
            Runtime.getRuntime().addShutdownHook(Thread {
                log.info("Shutdown hook: destroying all workers...")
                shutdownAll()
            })
            shutdownHookRegistered = true
        }
    }

    fun shutdownAll() {
        for (id in workers.keys.toList()) {
            try {
                unloadApk(id, clearCache = false)
            } catch (e: Exception) {
                log.warn("Failed to unload {}: {}", id, e.message)
            }
        }
    }

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun calculateMaxHeap(file: File, requestedMaxHeap: String?): String {
        if (!requestedMaxHeap.isNullOrBlank()) {
            val v = requestedMaxHeap.removePrefix("-Xmx")
            require(v.matches(Regex("^\\d+[kKmMgG]$"))) { "Invalid max_heap format: $requestedMaxHeap (expected e.g. 4g)" }
            return "-Xmx$v"
        }
        // 部署级默认堆：长期机器偏好
        val envXmx = System.getenv("JADX_WORKER_XMX")
        if (!envXmx.isNullOrBlank()) {
            return if (envXmx.startsWith("-Xmx")) envXmx else "-Xmx${envXmx.removePrefix("-Xmx")}"
        }
        val sizeMb = if (file.isFile) file.length() / (1024 * 1024) else 50
        return when {
            sizeMb < 20 -> "-Xmx2g"
            sizeMb < 50 -> "-Xmx4g"
            sizeMb < 100 -> "-Xmx6g"
            else -> "-Xmx8g"
        }
    }

    private fun calculateTimeoutMs(file: File): Long {
        val envSec = System.getenv("JADX_WORKER_TIMEOUT_SEC")?.toLongOrNull()
        if (envSec != null && envSec > 0) return envSec * 1000L
        val sizeMb = if (file.isFile) file.length() / (1024 * 1024) else 50
        val calculatedSec = 60L + (sizeMb / 10L) * 30L
        return calculatedSec.coerceAtLeast(60L).coerceAtMost(600L) * 1000L
    }

    private fun readTailLog(logFile: File, linesCount: Int = 15): String {
        if (!logFile.exists()) return "Log file does not exist."
        return try {
            logFile.readLines().takeLast(linesCount).joinToString("\n")
        } catch (e: Exception) {
            "Failed to read log: ${e.message}"
        }
    }

    private fun activeInstancesHint(): String {
        val list = listApks()
        if (list.isEmpty()) return "No other active apk instances."
        val lines = list.joinToString("\n") { m ->
            "- apk_id=${m["apk_id"]} path=${m["apk_path"]} ready=${m["is_ready"]}"
        }
        return "Active instances (${list.size}). Call apk_unload on unused apk_id to free memory:\n$lines"
    }

    private fun validateApkPath(apkPath: String): File {
        if (apkPath.contains(",")) {
            val parts = apkPath.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "Empty apk_path" }
            for (p in parts) {
                require(File(p).exists()) { "Target APK file not found at path: $p" }
            }
            return File(parts[0])
        }
        val file = File(apkPath)
        if (!file.exists()) throw IllegalArgumentException("Target APK file not found at path: $apkPath")
        return file
    }

    private fun reapDeadWorkers() {
        val dead = workers.entries.filter { !it.value.process.isAlive }.map { it.key }
        for (id in dead) {
            workers.remove(id)
            log.info("Reaped dead worker apk_id={}", id)
        }
    }

    fun loadApk(apkPath: String, maxHeap: String? = null, deobf: Boolean = true): Map<String, Any> {
        val file = validateApkPath(apkPath)
        val apkId: String
        val port: Int
        val xmxArg: String
        val timeoutMs: Long
        val proc: Process
        val workerLogFile: File

        lock.withLock {
            reapDeadWorkers()
            apkId = UUID.randomUUID().toString()
            val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
            val classPath = System.getProperty("java.class.path")
            port = findAvailablePort()
            val masterPid = ProcessHandle.current().pid()
            xmxArg = calculateMaxHeap(file, maxHeap)
            timeoutMs = calculateTimeoutMs(file)

            log.info("Creating Worker path={} apk_id={} port={} heap={}", apkPath, apkId, port, xmxArg)

            val logDir = File("logs")
            if (!logDir.exists()) logDir.mkdirs()
            workerLogFile = File(logDir, "worker_$apkId.log")

            // Worker 仅本机回环：Master 代理走 127.0.0.1，不对外暴露 Worker 端口
            val pb = ProcessBuilder(
                javaBin,
                xmxArg,
                "-cp", classPath,
                "com.yyang.jadx_server.worker.JadxWorkerMainKt",
                "--apk", apkPath,
                "--port", port.toString(),
                "--apk-id", apkId,
                "--master-pid", masterPid.toString(),
                "--deobf", deobf.toString()
            )
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workerLogFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(workerLogFile))
            pb.environment()["JADX_ROLE"] = "worker"
            pb.environment()["JADX_APK_ID"] = apkId

            proc = pb.start()
            proc.onExit().thenAccept {
                workers.remove(apkId)
                log.info("Worker exited apk_id={} code={}", apkId, it.exitValue())
            }
        }

        val startTime = System.currentTimeMillis()
        var isReady = false
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (!proc.isAlive) {
                val exitCode = try { proc.exitValue() } catch (_: Exception) { -1 }
                val tailLog = readTailLog(workerLogFile)
                val oom = exitCode == 137 || exitCode == 9
                val errorReason = if (oom) {
                    "Worker OOM/killed (exit $exitCode). Heap was $xmxArg. " +
                        "Try larger max_heap on apk_load, or unload unused instances first. " +
                        activeInstancesHint()
                } else {
                    "Worker exited with code $exitCode. ${activeInstancesHint()}\n--- Tail ---\n$tailLog"
                }
                throw IllegalStateException("Worker startup failed (apk_id=$apkId): $errorReason")
            }
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(1))
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200 && resp.body().contains("\"isLoaded\":true")) {
                    isReady = true
                    break
                }
            } catch (_: Exception) {
            }
            Thread.sleep(200)
        }

        if (!isReady) {
            proc.destroyForcibly()
            proc.waitFor(10, TimeUnit.SECONDS)
            val tailLog = readTailLog(workerLogFile)
            throw IllegalStateException(
                "Worker health check failed within ${timeoutMs / 1000}s (apk_id=$apkId). " +
                    "${activeInstancesHint()}\n--- Tail ---\n$tailLog"
            )
        }

        workers[apkId] = WorkerInstance(apkId, apkPath, port, proc, isReady = true)
        log.info("Worker ready apk_id={}", apkId)
        return mapOf(
            "status" to "success",
            "apk_id" to apkId,
            "apk_path" to apkPath,
            "worker_port" to port,
            "deobf" to deobf
        )
    }

    fun unloadApk(apkId: String, clearCache: Boolean = false): Map<String, Any> {
        val instance = lock.withLock {
            workers.remove(apkId)
                ?: throw IllegalArgumentException("No running instance found for apk_id '$apkId'.")
        }
        instance.isReady = false
        if (instance.process.isAlive) {
            log.info("Destroying Worker apk_id={}", apkId)
            instance.process.destroyForcibly()
            instance.process.waitFor(30, TimeUnit.SECONDS)
        }
        if (clearCache) {
            try {
                val cacheRoot = JadxCacheManager.resolveCacheRoot(instance.apkPath)
                if (cacheRoot.exists()) cacheRoot.deleteRecursively()
            } catch (e: Exception) {
                log.warn("clear_cache failed: {}", e.message)
            }
        }
        return mapOf(
            "status" to "success",
            "message" to "Worker destroyed and memory reclaimed.",
            "apk_id" to apkId
        )
    }

    fun listApks(): List<Map<String, Any>> {
        reapDeadWorkers()
        return workers.values.map {
            mapOf(
                "apk_id" to it.apkId,
                "apk_path" to it.apkPath,
                "is_ready" to (it.isReady && it.process.isAlive),
                "start_time" to it.startTime,
                "worker_port" to it.port
            )
        }
    }

    fun isWorkerAlive(apkId: String): Boolean {
        val w = workers[apkId] ?: return false
        if (!w.process.isAlive) {
            workers.remove(apkId)
            return false
        }
        return w.isReady
    }

    fun sendWorkerRequest(
        apkId: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120
    ): String {
        val instance = workers[apkId]
            ?: throw IllegalArgumentException("No running instance found for apk_id '$apkId'.")
        if (!instance.isReady || !instance.process.isAlive) {
            workers.remove(apkId)
            throw IllegalStateException("Worker for apk_id '$apkId' is not ready or has terminated.")
        }
        val queryString = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, Charsets.UTF_8) + "=" +
                    java.net.URLEncoder.encode(v, Charsets.UTF_8)
            }
        } else ""
        val targetUrl = "http://127.0.0.1:${instance.port}$path$queryString"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))
            .GET()
            .timeout(Duration.ofSeconds(timeoutSec.coerceIn(5, 600)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            throw IllegalStateException("Worker error ${response.statusCode()}: ${response.body()}")
        }
        return response.body()
    }
}
