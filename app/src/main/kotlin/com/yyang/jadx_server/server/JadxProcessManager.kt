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
     * Dynamically load a new APK and generate a UUID identifier.
     */
    fun loadApk(apkPath: String): Map<String, Any> {
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

            println("[ProcessManager] Creating isolated Worker process for $apkPath (apk_id: $apkId, assigned port: $port)...")

            val pb = ProcessBuilder(
                javaBin,
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
            val workerLogFile = File(logDir, "worker.log")
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
            while (System.currentTimeMillis() - startTime < 60000) {
                if (!proc.isAlive) {
                    throw IllegalStateException("Worker sub-process (apk_id: $apkId) exited unexpectedly during startup.")
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
                proc.destroyForcibly()
                throw IllegalStateException("Worker sub-process (apk_id: $apkId) failed health check within 60 seconds.")
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
