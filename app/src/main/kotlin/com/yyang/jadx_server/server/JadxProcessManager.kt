package com.yyang.jadx_server.server

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Worker sub-process lifecycle and multi-APK process pool manager.
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

    private val lock = ReentrantLock()
    private val workers = ConcurrentHashMap<String, WorkerInstance>()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(120))
        .build()

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    /**
     * 根据文件大小及外部输入/环境变量动态计算 JVM -Xmx 堆内存上限。
     * 依据：JADX load() 阶段需要构建全量 ClassNode 及全局 XRef 引用链，大型 APK 产生数百万节点，内存消耗与文件体积成正比。
     */
    private fun calculateMaxHeap(file: File, requestedMaxHeap: String?): String {
        if (!requestedMaxHeap.isNullOrBlank()) {
            return if (requestedMaxHeap.startsWith("-Xmx")) requestedMaxHeap else "-Xmx$requestedMaxHeap"
        }
        val envXmx = System.getenv("JADX_WORKER_XMX")
        if (!envXmx.isNullOrBlank()) {
            return if (envXmx.startsWith("-Xmx")) envXmx else "-Xmx$envXmx"
        }
        val sizeMb = file.length() / (1024 * 1024)
        return when {
            sizeMb < 20 -> "-Xmx2g"
            sizeMb < 50 -> "-Xmx4g"
            sizeMb < 100 -> "-Xmx6g"
            else -> "-Xmx8g"
        }
    }

    /**
     * 根据 APK 文件体积动态计算健康检查超时时长（单位：毫秒）。
     */
    private fun calculateTimeoutMs(file: File): Long {
        val envSec = System.getenv("JADX_WORKER_TIMEOUT_SEC")?.toLongOrNull()
        if (envSec != null && envSec > 0) {
            return envSec * 1000L
        }
        val sizeMb = file.length() / (1024 * 1024)
        val calculatedSec = 60L + (sizeMb / 10L) * 30L
        return calculatedSec.coerceAtLeast(60L).coerceAtMost(600L) * 1000L
    }

    /**
     * 辅助函数：读取指定 Worker 日志文件的末尾若干行，用于崩溃诊断。
     */
    private fun readTailLog(logFile: File, linesCount: Int = 15): String {
        if (!logFile.exists()) return "Log file does not exist."
        return try {
            val lines = logFile.readLines()
            lines.takeLast(linesCount).joinToString("\n")
        } catch (e: Exception) {
            "Failed to read log: ${e.message}"
        }
    }

    /**
     * Dynamically load a new APK and generate a UUID identifier.
     * @param apkPath Target APK file absolute path.
     * @param maxHeap Optional explicit JVM max heap setting (e.g., "4g", "8g"). If omitted, calculated dynamically.
     */
    fun loadApk(apkPath: String, maxHeap: String? = null): Map<String, Any> {
        val file = File(apkPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Target APK file not found at path: $apkPath")
        }

        lock.withLock {
            val apkId = UUID.randomUUID().toString()
            val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
            val classPath = System.getProperty("java.class.path")
            val port = findAvailablePort()
            val masterPid = ProcessHandle.current().pid()

            val xmxArg = calculateMaxHeap(file, maxHeap)
            val timeoutMs = calculateTimeoutMs(file)
            println("[ProcessManager] Creating isolated Worker process for $apkPath (apk_id: $apkId, assigned port: $port, heap: $xmxArg, timeout: ${timeoutMs / 1000}s)...")

            val pb = ProcessBuilder(
                javaBin,
                xmxArg,
                "-cp", classPath,
                "com.yyang.jadx_server.worker.JadxWorkerMainKt",
                "--apk", apkPath,
                "--port", port.toString(),
                "--master-pid", masterPid.toString()
            )
            val logDir = File("logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val workerLogFile = File(logDir, "worker_$apkId.log")
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workerLogFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(workerLogFile))

            val proc = pb.start()
            val instance = WorkerInstance(
                apkId = apkId,
                apkPath = apkPath,
                port = port,
                process = proc
            )

            // Health check polling until Worker is ready
            val startTime = System.currentTimeMillis()
            var isReady = false
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    val tailLog = readTailLog(workerLogFile)
                    val errorReason = if (exitCode == 137 || exitCode == 9) {
                        "Worker process killed by OS (Out Of Memory / Exit Code $exitCode). Heap setting: $xmxArg. Try specifying a larger max_heap parameter."
                    } else {
                        "Worker process exited unexpectedly with Exit Code $exitCode."
                    }
                    throw IllegalStateException("Worker sub-process (apk_id: $apkId) failed during startup: $errorReason\n--- Tail Logs ---\n$tailLog")
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
                } catch (e: Exception) {
                    // Worker starting up...
                }
                Thread.sleep(200)
            }

            if (!isReady) {
                val isAlive = proc.isAlive
                proc.destroyForcibly()
                val tailLog = readTailLog(workerLogFile)
                throw IllegalStateException("Worker sub-process (apk_id: $apkId) failed health check within ${timeoutMs / 1000} seconds (wasAlive: $isAlive).\n--- Tail Logs ---\n$tailLog")
            }

            instance.isReady = true
            workers[apkId] = instance
            println("[ProcessManager] Worker sub-process started successfully. apk_id: $apkId, target APK: $apkPath")
            return mapOf(
                "status" to "success",
                "apk_id" to apkId,
                "apk_path" to apkPath,
                "worker_port" to port
            )
        }
    }

    /**
     * Unload Worker sub-process for specified apk_id and reclaim heap memory.
     */
    fun unloadApk(apkId: String, clearCache: Boolean = false): Map<String, Any> {
        lock.withLock {
            val instance = workers.remove(apkId)
                ?: throw IllegalArgumentException("No running instance found for apk_id '$apkId'.")

            instance.isReady = false
            if (instance.process.isAlive) {
                println("[ProcessManager] Destroying running Worker sub-process (apk_id: $apkId)...")
                instance.process.destroyForcibly()
                instance.process.waitFor()
            }

            if (clearCache) {
                try {
                    val file = File(instance.apkPath)
                    val parent = file.parentFile ?: File(".")
                    val cacheDir = File(parent, "${file.name}_jadx_cache")
                    if (cacheDir.exists()) cacheDir.deleteRecursively()
                } catch (e: Exception) {
                    // Ignore cache cleanup errors
                }
            }

            return mapOf(
                "status" to "success",
                "message" to "Worker sub-process destroyed and memory reclaimed.",
                "apk_id" to apkId
            )
        }
    }

    /**
     * List all active APK instances running in the system.
     */
    fun listApks(): List<Map<String, Any>> {
        return workers.values.map {
            mapOf(
                "apk_id" to it.apkId,
                "apk_path" to it.apkPath,
                "is_ready" to (it.isReady && it.process.isAlive),
                "start_time" to it.startTime
            )
        }
    }

    /**
     * Check if a Worker instance with given apkId exists and is alive.
     */
    fun isWorkerAlive(apkId: String): Boolean {
        val w = workers[apkId] ?: return false
        return w.isReady && w.process.isAlive
    }

    /**
     * Send internal HTTP request to a specific Worker sub-process and return response string.
     */
    fun sendWorkerRequest(apkId: String, path: String, params: Map<String, String> = emptyMap()): String {
        val instance = workers[apkId]
            ?: throw IllegalArgumentException("No running instance found for apk_id '$apkId'.")
        if (!instance.isReady || !instance.process.isAlive) {
            throw IllegalStateException("Worker sub-process for apk_id '$apkId' is not ready or has terminated.")
        }

        val queryString = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8) + "=" +
                        java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)
            }
        } else ""

        val targetUrl = "http://127.0.0.1:${instance.port}$path$queryString"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))
            .GET()
            .timeout(Duration.ofSeconds(120))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            throw IllegalStateException("Worker node returned error ${response.statusCode()}: ${response.body()}")
        }
        return response.body()
    }

    /**
     * Transparently proxy HTTP request to Worker sub-process corresponding to apk_id.
     */
    fun proxyToWorker(apkId: String, exchange: HttpExchange) {
        val instance = workers[apkId]
        if (instance == null || !instance.isReady || !instance.process.isAlive) {
            ResponseUtils.sendError(exchange, 404, "No active Worker sub-process found for apk_id '$apkId'.")
            return
        }

        val targetPort = instance.port
        val uri = exchange.requestURI
        val targetUrl = "http://127.0.0.1:$targetPort${uri.rawPath}${if (uri.rawQuery != null) "?" + uri.rawQuery else ""}"
        var headersSent = false

        try {
            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))

            val method = exchange.requestMethod.uppercase()
            when (method) {
                "GET" -> reqBuilder.GET()
                "POST" -> reqBuilder.POST(HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody })
                "DELETE" -> reqBuilder.DELETE()
                else -> reqBuilder.method(method, HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody })
            }

            val response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")

            exchange.sendResponseHeaders(response.statusCode(), 0)
            headersSent = true

            response.body().use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            if (headersSent) {
                System.err.println("[ProcessManager] IO error during streaming proxy response: ${e.message}")
                try { exchange.responseBody.close() } catch (ignored: Exception) {}
            } else {
                ResponseUtils.sendError(exchange, 500, "Proxy error: ${e.message}")
            }
        }
    }
}
