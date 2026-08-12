package com.yyang.jadx_server.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.yyang.jadx_server.service.DecompileException

/**
 * HTTP 响应与参数工具。
 * WHY: Master 代理前若 parse 消费 POST body，转发时 body 为空（审查 S2）。
 * DECISION: parseRequestParams 支持预读 body 字节；CORS 放行 X-Apk-Id。
 */
object ResponseUtils {

    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    data class ParsedRequest(
        val params: Map<String, String>,
        /** 原始 POST body，供代理原样转发；GET 为 null */
        val rawBody: ByteArray?
    )

    fun parseRequest(exchange: HttpExchange): ParsedRequest {
        val map = mutableMapOf<String, String>()
        map.putAll(parseQueryParams(exchange.requestURI.query))
        var rawBody: ByteArray? = null

        if (exchange.requestMethod.equals("POST", ignoreCase = true) ||
            exchange.requestMethod.equals("PUT", ignoreCase = true)
        ) {
            try {
                rawBody = exchange.requestBody.readBytes()
                val bodyStr = String(rawBody, Charsets.UTF_8).trim()
                if (bodyStr.isNotEmpty()) {
                    if (bodyStr.startsWith("{")) {
                        val jsonObject = gson.fromJson(bodyStr, com.google.gson.JsonObject::class.java)
                        for ((k, v) in jsonObject.entrySet()) {
                            if (!v.isJsonNull) {
                                map[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                            }
                        }
                    } else if (bodyStr.contains("=")) {
                        map.putAll(parseQueryParams(bodyStr))
                    }
                }
            } catch (e: Exception) {
                // 保留 rawBody 供代理；参数缺失由业务层报 400
            }
        }
        return ParsedRequest(map, rawBody)
    }

    /** 兼容旧调用：仅要 params */
    fun parseRequestParams(exchange: HttpExchange): Map<String, String> = parseRequest(exchange).params

    fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            if (param.isEmpty()) continue
            val pair = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(pair[0], "UTF-8")
            result[key] = if (pair.size > 1) java.net.URLDecoder.decode(pair[1], "UTF-8") else ""
        }
        return result
    }

    fun handleOptions(exchange: HttpExchange): Boolean {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendSuccess(exchange, emptyMap<String, String>())
            return true
        }
        return false
    }

    fun sendSuccess(exchange: HttpExchange, data: Any) {
        sendJsonResponse(exchange, 200, gson.toJson(data))
    }

    fun sendError(exchange: HttpExchange, statusCode: Int, message: String, code: String? = null) {
        val errorMap = mutableMapOf(
            "status" to "error",
            "error" to message,
            "message" to message
        )
        if (code != null) errorMap["code"] = code
        sendJsonResponse(exchange, statusCode, gson.toJson(errorMap))
    }

    fun sendDecompileError(exchange: HttpExchange, e: DecompileException) {
        sendError(exchange, e.httpStatus, e.message ?: e.code, e.code)
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        // WHY: 文档推荐 X-Apk-Id，但 CORS 未放行会导致浏览器预检失败
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Apk-Id")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
