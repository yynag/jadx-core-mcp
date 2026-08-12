package com.yyang.jadx_server.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yyang.jadx_server.service.DecompileException
import com.yyang.jadx_server.service.JadxEngine
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Worker 进程内 HTTP（仅业务 API）。
 *
 * WHY: Master 已迁 Ktor（REST+MCP）；Worker 保持轻量 JDK HttpServer，且只绑 127.0.0.1。
 * DECISION: 无 /apk/load；无 ProcessManager。
 */
class JadxHttpServer(
    private val port: Int,
    cacheInstanceKey: String = "worker"
) {
    private val log = LoggerFactory.getLogger(JadxHttpServer::class.java)
    val engine = JadxEngine(cacheInstanceKey = cacheInstanceKey)
    private var httpServer: HttpServer? = null

    fun start() {
        // 仅本机：Master 通过 127.0.0.1 代理，不对外
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newCachedThreadPool()
        httpServer = server

        server.createContext("/health") { exchange ->
            if (ResponseUtils.handleOptions(exchange)) return@createContext
            ResponseUtils.sendSuccess(
                exchange,
                mapOf(
                    "status" to "ok",
                    "role" to "worker",
                    "apk" to (engine.currentApkPath ?: ""),
                    "classesCount" to engine.classesCount,
                    "isLoaded" to (engine.currentApkPath != null)
                )
            )
        }

        val forbid = { exchange: HttpExchange ->
            if (!ResponseUtils.handleOptions(exchange)) {
                ResponseUtils.sendError(exchange, 403, "Worker does not expose /apk/*", "WORKER_FORBIDDEN")
            }
        }
        server.createContext("/apk/load", forbid)
        server.createContext("/apk/unload", forbid)
        server.createContext("/apk/list", forbid)

        val businessHandler = { exchange: HttpExchange ->
            if (!ResponseUtils.handleOptions(exchange)) {
                handleLocalBusinessRequest(exchange)
            }
        }
        listOf(
            "/meta/manifest", "/meta/summary", "/meta/classes", "/meta/methods", "/meta/fields", "/meta/main-activity",
            "/decompile/java", "/decompile/smali", "/decompile/method",
            "/resource/strings", "/resource/list", "/resource/file",
            "/xref/class", "/xref/method", "/xref/field",
            "/search/classes", "/search/method"
        ).forEach { path -> server.createContext(path, businessHandler) }

        server.start()
        log.info("Worker HTTP on 127.0.0.1:{}", port)
        println("Worker HTTP 127.0.0.1:$port")
    }

    fun stop() {
        try {
            httpServer?.stop(0)
        } catch (e: Exception) {
            log.warn("stop error: {}", e.message)
        }
    }

    private fun handleLocalBusinessRequest(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        try {
            val params = ResponseUtils.parseRequestParams(exchange)
            when (path) {
                "/meta/manifest" -> ResponseUtils.sendSuccess(exchange, mapOf("content" to engine.getManifest()))
                "/meta/summary" -> {
                    val activity = engine.getMainActivity()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf(
                            "classesCount" to engine.classesCount,
                            "mainActivity" to (activity?.fullName ?: ""),
                            "currentApkPath" to (engine.currentApkPath ?: "")
                        )
                    )
                }
                "/meta/classes" -> {
                    val offset = params["offset"]?.toDoubleOrNull()?.toInt() ?: 0
                    val count = params["count"]?.toDoubleOrNull()?.toInt() ?: 50
                    val pkg = params["package"] ?: ""
                    val classes = if (pkg.isNotEmpty()) {
                        engine.getClassNamesByPackage(pkg, offset, count)
                    } else {
                        engine.getAllClassNames(offset, count)
                    }
                    val total = if (pkg.isNotEmpty()) engine.countClassesByPackage(pkg) else engine.classesCount
                    ResponseUtils.sendSuccess(exchange, mapOf("total" to total, "classes" to classes))
                }
                "/meta/methods" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("class_name" to javaClass.fullName, "methods" to javaClass.methods.map { it.name })
                    )
                }
                "/meta/fields" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("class_name" to javaClass.fullName, "fields" to javaClass.fields.map { it.name })
                    )
                }
                "/meta/main-activity" -> {
                    val activityClass = engine.getMainActivity()
                    if (activityClass != null) {
                        val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                        ResponseUtils.sendSuccess(
                            exchange,
                            mapOf(
                                "class_name" to activityClass.fullName,
                                "code" to engine.getClassSource(activityClass, timeout)
                            )
                        )
                    } else {
                        ResponseUtils.sendError(exchange, 404, "Main Activity not found", DecompileException.NOT_FOUND)
                    }
                }
                "/decompile/java" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("class_name" to javaClass.fullName, "code" to engine.getClassSource(javaClass, timeout))
                    )
                }
                "/decompile/smali" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("class_name" to javaClass.fullName, "smali" to engine.getClassSmali(javaClass, timeout))
                    )
                }
                "/decompile/method" -> {
                    val methodName = params["method_name"]
                        ?: throw DecompileException(DecompileException.INVALID_ARGUMENT, "Missing 'method_name'", 400)
                    val javaClass = engine.requireClass(params["class_name"])
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf(
                            "class_name" to javaClass.fullName,
                            "method_name" to methodName,
                            "code" to engine.getMethodSourceCode(javaClass, methodName, timeout)
                        )
                    )
                }
                "/resource/strings" -> {
                    val offset = params["offset"]?.toDoubleOrNull()?.toInt() ?: 0
                    val count = params["count"]?.toDoubleOrNull()?.toInt() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("strings" to engine.getStrings(offset, count)))
                }
                "/resource/list" -> {
                    val offset = params["offset"]?.toDoubleOrNull()?.toInt() ?: 0
                    val count = params["count"]?.toDoubleOrNull()?.toInt() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("files" to engine.getAllResourceFileNames(offset, count)))
                }
                "/resource/file" -> {
                    val fileName = params["file_name"] ?: ""
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("file_name" to fileName, "content" to engine.getResourceFile(fileName))
                    )
                }
                "/xref/class" -> {
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf(
                            "references" to engine.getXrefsToClass(
                                params["class_name"] ?: "",
                                params["offset"]?.toDoubleOrNull()?.toInt() ?: 0,
                                params["count"]?.toDoubleOrNull()?.toInt() ?: 50,
                                timeout
                            )
                        )
                    )
                }
                "/xref/method" -> {
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf(
                            "references" to engine.getXrefsToMethod(
                                params["class_name"] ?: "",
                                params["method_name"] ?: "",
                                params["offset"]?.toDoubleOrNull()?.toInt() ?: 0,
                                params["count"]?.toDoubleOrNull()?.toInt() ?: 50,
                                timeout
                            )
                        )
                    )
                }
                "/xref/field" -> {
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf(
                            "references" to engine.getXrefsToField(
                                params["class_name"] ?: "",
                                params["field_name"] ?: "",
                                params["offset"]?.toDoubleOrNull()?.toInt() ?: 0,
                                params["count"]?.toDoubleOrNull()?.toInt() ?: 50,
                                timeout
                            )
                        )
                    )
                }
                "/search/classes" -> {
                    val term = params["search_term"] ?: ""
                    val pkg = params["package"] ?: ""
                    val searchIn = params["search_in"] ?: "class"
                    val offset = params["offset"]?.toDoubleOrNull()?.toInt() ?: 0
                    val count = params["count"]?.toDoubleOrNull()?.toInt() ?: 50
                    val timeout = params["timeout"]?.toDoubleOrNull()?.toLong()
                    val maxScan = params["max_scan"]?.toDoubleOrNull()?.toInt()
                    val maxDecompile = params["max_decompile"]?.toDoubleOrNull()?.toInt()
                    ResponseUtils.sendSuccess(
                        exchange,
                        engine.searchClassesByKeyword(term, pkg, searchIn, offset, count, timeout, maxScan, maxDecompile)
                    )
                }
                "/search/method" -> {
                    val methodName = params["method_name"] ?: ""
                    val offset = params["offset"]?.toDoubleOrNull()?.toInt() ?: 0
                    val count = params["count"]?.toDoubleOrNull()?.toInt() ?: 50
                    ResponseUtils.sendSuccess(
                        exchange,
                        mapOf("matches" to engine.searchMethodByName(methodName, offset, count))
                    )
                }
                else -> ResponseUtils.sendError(exchange, 404, "Unknown endpoint: $path")
            }
        } catch (e: DecompileException) {
            ResponseUtils.sendDecompileError(exchange, e)
        } catch (e: IllegalArgumentException) {
            ResponseUtils.sendError(exchange, 400, e.message ?: "Bad Request")
        } catch (e: IllegalStateException) {
            ResponseUtils.sendError(exchange, 400, e.message ?: "Bad Request")
        } catch (e: Exception) {
            log.error("Business handler error", e)
            ResponseUtils.sendError(exchange, 500, e.message ?: "Internal Server Error")
        }
    }
}
