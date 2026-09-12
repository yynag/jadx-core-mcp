package com.yyang.jadx_server.server

import com.yyang.jadx_server.mcp.JadxMcpServer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Master 对外入口：同一端口 REST + MCP Streamable HTTP。
 *
 * WHY: OpenCode `type: remote` 需要 MCP-over-HTTP URL；局域网 Agent 与脚本共用一台大内存机。
 * DECISION:
 * - 固定 0.0.0.0（本机+局域网），无 token（内网信任）
 * - `/mcp` = Streamable HTTP（SDK mcpStreamableHttp）；DNS rebinding 关闭/放宽以允许局域网 Host
 * - REST 路径与 Worker 业务代理保持兼容
 * EVIDENCE: https://opencode.ai/docs/mcp-servers/#remote
 */
class MasterKtorServer(
    private val port: Int = 8650,
    private val processManager: JadxProcessManager = JadxProcessManager()
) {
    private val log = LoggerFactory.getLogger(MasterKtorServer::class.java)
    private val mcpTools = JadxMcpServer(processManager)
    private val gson = ResponseUtils.gson

    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    fun start(wait: Boolean = true) {
        log.info("Master starting on 0.0.0.0:{} (REST + MCP /mcp)", port)
        println("Master listening 0.0.0.0:$port")
        println("  REST:  http://<host>:$port/health , /apk/* , /meta/* ...")
        println("  MCP:   http://<host>:$port/mcp  (OpenCode type=remote)")
        println("WARNING: No auth. Trusted LAN only.")

        val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Apk-Id")
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
            }

            // WHY: 默认 DNS rebinding 只放行 localhost，局域网 Host: 192.168.x.x 会被拒
            // DECISION: enableDnsRebindingProtection=false（无 token 内网场景）
            mcpStreamableHttp(
                path = "/mcp",
                enableDnsRebindingProtection = false,
                allowedHosts = null,
                allowedOrigins = null
            ) {
                mcpTools.createServer()
            }

            routing {
                get("/health") {
                    call.respondJson(
                        mapOf(
                            "status" to "ok",
                            "role" to "master",
                            "activeApksCount" to processManager.listApks().size,
                            "isMaster" to true,
                            "mcp" to "/mcp",
                            "bind" to "0.0.0.0"
                        )
                    )
                }

                get("/apk/list") {
                    call.respondJson(mapOf("apks" to processManager.listApks()))
                }

                post("/apk/load") {
                    try {
                        val params = parseParams(call)
                        val apkPath = params["apk_path"]
                        if (apkPath.isNullOrBlank()) {
                            call.respondError(400, "Missing required parameter 'apk_path'")
                            return@post
                        }
                        val result = withContext(Dispatchers.IO) {
                            processManager.loadApk(
                                apkPath,
                                params["max_heap"],
                                params["deobf"]?.toBooleanStrictOrNull() ?: true
                            )
                        }
                        call.respondJson(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondError(400, e.message ?: "Bad Request")
                    } catch (e: Exception) {
                        call.respondError(500, e.message ?: "Internal Server Error")
                    }
                }

                post("/apk/unload") {
                    try {
                        val params = parseParams(call)
                        val apkId = extractApkId(call, params)
                        if (apkId.isNullOrBlank()) {
                            call.respondError(400, "Missing required parameter 'apk_id'")
                            return@post
                        }
                        val clear = params["clear_cache"]?.toBoolean() ?: false
                        val result = withContext(Dispatchers.IO) {
                            processManager.unloadApk(apkId, clear)
                        }
                        call.respondJson(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondError(400, e.message ?: "Bad Request")
                    } catch (e: Exception) {
                        call.respondError(500, e.message ?: "Internal Server Error")
                    }
                }

                // 业务路径：校验 apk_id 后代理到 Worker
                val businessPaths = listOf(
                    "/meta/manifest", "/meta/summary", "/meta/classes", "/meta/methods", "/meta/fields", "/meta/main-activity",
                    "/meta/components",
                    "/decompile/java", "/decompile/smali", "/decompile/method",
                    "/resource/strings", "/resource/list", "/resource/file", "/resource/id",
                    "/xref/class", "/xref/method", "/xref/field",
                    "/search/classes", "/search/method", "/search/field", "/search/string",
                    "/rename"
                )
                for (path in businessPaths) {
                    get(path) { proxyBusiness(call) }
                    post(path) { proxyBusiness(call) }
                    options(path) { call.respondText("", status = HttpStatusCode.OK) }
                }
            }
        }
        engine = server
        server.start(wait = wait)
    }

    fun stop() {
        try {
            processManager.shutdownAll()
            engine?.stop(1000, 2000)
        } catch (e: Exception) {
            log.warn("stop: {}", e.message)
        }
    }

    private suspend fun proxyBusiness(call: ApplicationCall) {
        val params = parseParams(call)
        val apkId = extractApkId(call, params)
        if (apkId.isNullOrBlank()) {
            call.respondError(400, "Missing required parameter 'apk_id'")
            return
        }
        if (!processManager.isWorkerAlive(apkId)) {
            call.respondError(404, "No running instance found for apk_id '$apkId'")
            return
        }
        val path = call.request.path()
        val queryParams = call.request.queryParameters.entries()
            .associate { it.key to (it.value.firstOrNull() ?: "") }
            .toMutableMap()
        params.forEach { (k, v) -> if (k != "apk_id") queryParams.putIfAbsent(k, v) }
        try {
            val body = withContext(Dispatchers.IO) {
                processManager.sendWorkerRequest(apkId, path, queryParams)
            }
            call.respondText(body, ContentType.Application.Json)
        } catch (e: IllegalStateException) {
            // Worker 4xx/5xx 包装
            val msg = e.message ?: "Worker error"
            val code = Regex("""Worker error (\d+):""").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 500
            call.respondText(msg.substringAfter(": ").ifBlank { msg }, ContentType.Application.Json, HttpStatusCode.fromValue(code.coerceIn(400, 599)))
        } catch (e: Exception) {
            call.respondError(500, e.message ?: "Proxy error")
        }
    }

    private suspend fun parseParams(call: ApplicationCall): Map<String, String> {
        val map = mutableMapOf<String, String>()
        call.request.queryParameters.forEach { name, values ->
            map[name] = values.firstOrNull() ?: ""
        }
        if (call.request.httpMethod == HttpMethod.Post) {
            try {
                val bodyStr = call.receiveText().trim()
                if (bodyStr.startsWith("{")) {
                    val obj = gson.fromJson(bodyStr, com.google.gson.JsonObject::class.java)
                    for ((k, v) in obj.entrySet()) {
                        if (!v.isJsonNull) {
                            map[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return map
    }

    private fun extractApkId(call: ApplicationCall, params: Map<String, String>): String? {
        call.request.headers["X-Apk-Id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return params["apk_id"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun ApplicationCall.respondJson(data: Any, status: HttpStatusCode = HttpStatusCode.OK) {
        respondText(gson.toJson(data), ContentType.Application.Json, status)
    }

    private suspend fun ApplicationCall.respondError(code: Int, message: String) {
        respondJson(
            mapOf("status" to "error", "error" to message, "message" to message),
            HttpStatusCode.fromValue(code)
        )
    }

    fun processManager(): JadxProcessManager = processManager
}
