package com.yyang.jadx_server.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange

/**
 * Utility object for HTTP response sending and parameter extraction.
 * 
 * Responsibilities:
 * 1. Standardize JSON response creation and serialization.
 * 2. Extract and UTF-8 decode URL Query and POST body parameters.
 * 3. Automatically inject CORS headers for browser and tool compatibility.
 */
object ResponseUtils {

    /** Global Gson instance with HTML escaping disabled to preserve characters like < > in Java code */
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Parse request parameters (combining URL Query and POST Body JSON / Form-Data).
     */
    fun parseRequestParams(exchange: HttpExchange): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map.putAll(parseQueryParams(exchange.requestURI.query))

        if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
            try {
                val bodyStr = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText().trim()
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
                // Ignore body parsing exceptions
            }
        }
        return map
    }

    /**
     * Parse query string (e.g. "a=1&b=2") from URL.
     * Uses limit = 2 to prevent parameter values containing '=' from being truncated.
     * 
     * @param query Raw query string
     * @return Decoded key-value map
     */
    fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            if (param.isEmpty()) continue
            val pair = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(pair[0], "UTF-8")
            if (pair.size > 1) {
                result[key] = java.net.URLDecoder.decode(pair[1], "UTF-8")
            } else {
                result[key] = ""
            }
        }
        return result
    }

    /**
     * Handle OPTIONS preflight requests by sending 200 OK and returning true.
     */
    fun handleOptions(exchange: HttpExchange): Boolean {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendSuccess(exchange, emptyMap<String, String>())
            return true
        }
        return false
    }

    /**
     * Send 200 OK success response.
     */
    fun sendSuccess(exchange: HttpExchange, data: Any) {
        sendJsonResponse(exchange, 200, gson.toJson(data))
    }

    /**
     * Send error response (e.g. 400 Bad Request, 404 Not Found, 500 Internal Server Error).
     */
    fun sendError(exchange: HttpExchange, statusCode: Int, message: String) {
        val errorMap = mapOf(
            "status" to "error",
            "error" to message,
            "message" to message
        )
        sendJsonResponse(exchange, statusCode, gson.toJson(errorMap))
    }

    /**
     * Write HTTP headers and write JSON payload bytes to response body.
     */
    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        
        // Inject CORS headers (supporting GET, POST, OPTIONS)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")

        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}
