package com.yyang.jadx_server.server

import com.yyang.jadx_server.service.JadxEngine
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * HTTP service route dispatcher built on JDK HttpServer.
 * Follows /domain/action domain separation and explicit contracts.
 */
class JadxHttpServer(private val port: Int) {
    
    val engine = JadxEngine()
    val processManager = JadxProcessManager()

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newCachedThreadPool()

        // ==========================================
        // 1. Service control & dynamic loading APIs (/apk/* & /health)
        // ==========================================
        
        server.createContext("/health") { exchange ->
            if (ResponseUtils.handleOptions(exchange)) return@createContext
            if (engine.currentApkPath != null) {
                // Worker node health check
                val response = mapOf(
                    "status" to "ok",
                    "apk" to engine.currentApkPath!!,
                    "classesCount" to engine.classesCount,
                    "isLoaded" to true
                )
                ResponseUtils.sendSuccess(exchange, response)
            } else {
                // Master node health check
                val response = mapOf(
                    "status" to "ok",
                    "activeApksCount" to processManager.listApks().size,
                    "isMaster" to true
                )
                ResponseUtils.sendSuccess(exchange, response)
            }
        }

        server.createContext("/apk/list") { exchange ->
            if (ResponseUtils.handleOptions(exchange)) return@createContext
            ResponseUtils.sendSuccess(exchange, mapOf("apks" to processManager.listApks()))
        }
        
        server.createContext("/apk/load") { exchange ->
            if (ResponseUtils.handleOptions(exchange)) return@createContext
            try {
                val params = ResponseUtils.parseRequestParams(exchange)
                val apkPath = params["apk_path"]
                if (apkPath.isNullOrEmpty()) {
                    ResponseUtils.sendError(exchange, 400, "Missing required parameter 'apk_path'")
                    return@createContext
                }
                
                val result = processManager.loadApk(apkPath)
                ResponseUtils.sendSuccess(exchange, result)
            } catch (e: IllegalArgumentException) {
                ResponseUtils.sendError(exchange, 400, e.message ?: "Bad Request")
            } catch (e: Exception) {
                ResponseUtils.sendError(exchange, 500, e.message ?: "Internal Server Error")
            }
        }

        server.createContext("/apk/unload") { exchange ->
            if (ResponseUtils.handleOptions(exchange)) return@createContext
            try {
                val params = ResponseUtils.parseRequestParams(exchange)
                val apkId = extractApkId(exchange, params)
                if (apkId.isNullOrEmpty()) {
                    ResponseUtils.sendError(exchange, 400, "Missing required parameter 'apk_id'")
                    return@createContext
                }
                val clearCache = params["clear_cache"]?.toBoolean() ?: false
                val result = processManager.unloadApk(apkId, clearCache = clearCache)
                ResponseUtils.sendSuccess(exchange, result)
            } catch (e: IllegalArgumentException) {
                ResponseUtils.sendError(exchange, 400, e.message ?: "Bad Request")
            } catch (e: Exception) {
                ResponseUtils.sendError(exchange, 500, e.message ?: "Internal Server Error")
            }
        }

        // ==========================================
        // 2. /domain/action domain query APIs
        // ==========================================

        val proxyHandler = { exchange: HttpExchange ->
            if (!ResponseUtils.handleOptions(exchange)) {
                if (engine.currentApkPath != null) {
                    // Worker sub-process local handler
                    handleLocalBusinessRequest(exchange)
                } else {
                    // Master node: extract apk_id for validation and transparent proxy
                    val params = ResponseUtils.parseRequestParams(exchange)
                    val apkId = extractApkId(exchange, params)

                    if (apkId.isNullOrEmpty()) {
                        ResponseUtils.sendError(exchange, 400, "Missing required parameter 'apk_id'")
                    } else if (!processManager.isWorkerAlive(apkId)) {
                        ResponseUtils.sendError(exchange, 404, "No running instance found for apk_id '$apkId'")
                    } else {
                        processManager.proxyToWorker(apkId, exchange)
                    }
                }
            }
        }

        // Meta domain
        server.createContext("/meta/manifest", proxyHandler)
        server.createContext("/meta/summary", proxyHandler)
        server.createContext("/meta/classes", proxyHandler)
        server.createContext("/meta/methods", proxyHandler)
        server.createContext("/meta/fields", proxyHandler)
        server.createContext("/meta/main-activity", proxyHandler)

        // Decompile domain
        server.createContext("/decompile/java", proxyHandler)
        server.createContext("/decompile/smali", proxyHandler)
        server.createContext("/decompile/method", proxyHandler)

        // Resource domain
        server.createContext("/resource/strings", proxyHandler)
        server.createContext("/resource/list", proxyHandler)
        server.createContext("/resource/file", proxyHandler)

        // Xref domain
        server.createContext("/xref/class", proxyHandler)
        server.createContext("/xref/method", proxyHandler)
        server.createContext("/xref/field", proxyHandler)

        // Search domain
        server.createContext("/search/classes", proxyHandler)
        server.createContext("/search/method", proxyHandler)

        server.start()
        println("Server successfully started on port $port")
    }

    private fun extractApkId(exchange: HttpExchange, params: Map<String, String>): String? {
        val headerVal = exchange.requestHeaders.getFirst("X-Apk-Id")
        if (!headerVal.isNullOrBlank()) {
            return headerVal.trim()
        }
        val paramVal = params["apk_id"]
        if (!paramVal.isNullOrBlank()) {
            return paramVal.trim()
        }
        return null
    }

    private fun handleLocalBusinessRequest(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        try {
            val params = ResponseUtils.parseRequestParams(exchange)
            when (path) {
                "/meta/manifest" -> {
                    ResponseUtils.sendSuccess(exchange, mapOf("content" to engine.getManifest()))
                }
                "/meta/summary" -> {
                    val activity = engine.getMainActivity()
                    ResponseUtils.sendSuccess(exchange, mapOf(
                        "classesCount" to engine.classesCount,
                        "mainActivity" to (activity?.fullName ?: ""),
                        "currentApkPath" to (engine.currentApkPath ?: "")
                    ))
                }
                "/meta/classes" -> {
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    val pkg = params["package"] ?: ""
                    val classes = if (pkg.isNotEmpty()) {
                        engine.searchClassesByKeyword("", pkg, "class", offset, count)
                    } else {
                        engine.getAllClassNames(offset, count)
                    }
                    ResponseUtils.sendSuccess(exchange, mapOf("total" to engine.classesCount, "classes" to classes))
                }
                "/meta/methods" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val methods = javaClass.methods.map { it.methodNode.methodInfo.shortId }
                    ResponseUtils.sendSuccess(exchange, mapOf("class_name" to javaClass.fullName, "methods" to methods))
                }
                "/meta/fields" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val fields = javaClass.fields.map { it.fieldNode.fieldInfo.shortId }
                    ResponseUtils.sendSuccess(exchange, mapOf("class_name" to javaClass.fullName, "fields" to fields))
                }
                "/meta/main-activity" -> {
                    val activityClass = engine.getMainActivity()
                    if (activityClass != null) {
                        ResponseUtils.sendSuccess(exchange, mapOf("class_name" to activityClass.fullName, "code" to engine.getClassSource(activityClass)))
                    } else {
                        ResponseUtils.sendError(exchange, 404, "Main Activity not found")
                    }
                }
                "/decompile/java" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val code = engine.getClassSource(javaClass)
                    ResponseUtils.sendSuccess(exchange, mapOf("class_name" to javaClass.fullName, "code" to code))
                }
                "/decompile/smali" -> {
                    val javaClass = engine.requireClass(params["class_name"])
                    val smali = engine.getClassSmali(javaClass)
                    ResponseUtils.sendSuccess(exchange, mapOf("class_name" to javaClass.fullName, "smali" to smali))
                }
                "/decompile/method" -> {
                    val methodName = requireNotNull(params["method_name"]) { "Missing 'method_name'" }
                    val javaClass = engine.requireClass(params["class_name"])
                    val code = engine.getMethodSourceCode(javaClass, methodName)
                    ResponseUtils.sendSuccess(exchange, mapOf("class_name" to javaClass.fullName, "method_name" to methodName, "code" to code))
                }
                "/resource/strings" -> {
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("strings" to engine.getStrings(offset, count)))
                }
                "/resource/list" -> {
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("files" to engine.getAllResourceFileNames(offset, count)))
                }
                "/resource/file" -> {
                    val fileName = params["file_name"] ?: ""
                    ResponseUtils.sendSuccess(exchange, mapOf("file_name" to fileName, "content" to engine.getResourceFile(fileName)))
                }
                "/xref/class" -> {
                    val className = params["class_name"] ?: ""
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("references" to engine.getXrefsToClass(className, offset, count)))
                }
                "/xref/method" -> {
                    val className = params["class_name"] ?: ""
                    val methodName = params["method_name"] ?: ""
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("references" to engine.getXrefsToMethod(className, methodName, offset, count)))
                }
                "/xref/field" -> {
                    val className = params["class_name"] ?: ""
                    val fieldName = params["field_name"] ?: ""
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("references" to engine.getXrefsToField(className, fieldName, offset, count)))
                }
                "/search/classes" -> {
                    val term = params["search_term"] ?: ""
                    val pkg = params["package"] ?: ""
                    val searchIn = params["search_in"] ?: "code"
                    val offset = params["offset"]?.toIntOrNull() ?: 0
                    val count = params["count"]?.toIntOrNull() ?: 50
                    ResponseUtils.sendSuccess(exchange, mapOf("classes" to engine.searchClassesByKeyword(term, pkg, searchIn, offset, count)))
                }
                "/search/method" -> {
                    val methodName = params["method_name"] ?: ""
                    ResponseUtils.sendSuccess(exchange, mapOf("matches" to engine.searchMethodByName(methodName)))
                }
                else -> ResponseUtils.sendError(exchange, 404, "Unknown endpoint: $path")
            }
        } catch (e: IllegalArgumentException) {
            ResponseUtils.sendError(exchange, 400, e.message ?: "Bad Request")
        } catch (e: Exception) {
            ResponseUtils.sendError(exchange, 500, e.message ?: "Internal Server Error")
        }
    }
}
