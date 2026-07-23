package com.yyang.jadx_server.mcp

import com.yyang.jadx_server.server.JadxProcessManager
import com.google.gson.Gson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JADX Headless Stdio server based on official Model Context Protocol (MCP) Kotlin SDK.
 * 
 * Responsibilities:
 * 1. Declare and expose full suite of 20 JADX reverse-engineering tools;
 * 2. Bridge MCP client tool calls to underlying JadxProcessManager process pool and Worker nodes;
 * 3. Run in Stdio transport mode for AI Agent integrations (opencode, Claude Desktop, Cursor, etc.).
 */
class JadxMcpServer(
    private val processManager: JadxProcessManager,
    private val mcpOutputStream: java.io.OutputStream = System.out
) {

    private val gson = Gson()

    private fun createToolSchema(
        properties: Map<String, Pair<String, String>> = emptyMap(),
        required: List<String> = emptyList()
    ): ToolSchema {
        val propsJson = buildJsonObject {
            properties.forEach { (name, typeAndDesc) ->
                putJsonObject(name) {
                    put("type", typeAndDesc.first)
                    put("description", typeAndDesc.second)
                }
            }
        }
        return ToolSchema(
            properties = propsJson,
            required = required
        )
    }

    private fun getArgString(args: JsonObject?, key: String): String? {
        return args?.get(key)?.jsonPrimitive?.content
    }

    private fun getArgInt(args: JsonObject?, key: String): Int? {
        return args?.get(key)?.jsonPrimitive?.intOrNull
    }

    private fun getArgBoolean(args: JsonObject?, key: String): Boolean? {
        return args?.get(key)?.jsonPrimitive?.booleanOrNull
    }

    /**
     * Build and start MCP Stdio service, listening on stdin.
     */
    fun start(): Unit = runBlocking {
        val server = Server(
            serverInfo = Implementation(name = "jadx-core-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
            )
        )

        // =================================================================
        // 1. Service Lifecycle and Process Management Tools (apk_*)
        // =================================================================

        server.addTool(
            Tool(
                name = "apk_load",
                description = "Dynamically load an Android APK file, spawn an isolated Worker process and return a unique apk_id UUID.",
                inputSchema = createToolSchema(
                    properties = mapOf("apk_path" to Pair("string", "Absolute path to target APK file (Required)")),
                    required = listOf("apk_path")
                )
            )
        ) { request ->
            val apkPath = requireNotNull(getArgString(request.arguments, "apk_path")) { "Missing required parameter 'apk_path'" }
            val result = processManager.loadApk(apkPath)
            CallToolResult(content = listOf(TextContent(text = gson.toJson(result))))
        }

        server.addTool(
            Tool(
                name = "apk_unload",
                description = "Unload Worker sub-process for specified apk_id and release OS heap memory.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "clear_cache" to Pair("boolean", "Whether to clean cache files synchronously (default: false)")
                    ),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val clearCache = getArgBoolean(request.arguments, "clear_cache") ?: false
            val result = processManager.unloadApk(apkId, clearCache)
            CallToolResult(content = listOf(TextContent(text = gson.toJson(result))))
        }

        server.addTool(
            Tool(
                name = "apk_list",
                description = "Get list of all active loaded apk_ids and their metadata in the process pool.",
                inputSchema = createToolSchema()
            )
        ) { _ ->
            val result = mapOf("apks" to processManager.listApks())
            CallToolResult(content = listOf(TextContent(text = gson.toJson(result))))
        }

        // =================================================================
        // 2. Metadata and Structure Analysis Tools (meta_*)
        // =================================================================

        server.addTool(
            Tool(
                name = "meta_manifest",
                description = "Parse and extract raw text of AndroidManifest.xml for loaded APK.",
                inputSchema = createToolSchema(
                    properties = mapOf("apk_id" to Pair("string", "Target apk_id UUID")),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val json = processManager.sendWorkerRequest(apkId, "/meta/manifest")
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "meta_summary",
                description = "Get APK structural summary (total class count, main activity name, and APK path).",
                inputSchema = createToolSchema(
                    properties = mapOf("apk_id" to Pair("string", "Target apk_id UUID")),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val json = processManager.sendWorkerRequest(apkId, "/meta/summary")
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "meta_classes",
                description = "Get paginated list of fully qualified class names in APK, optionally filtered by package prefix.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "package" to Pair("string", "Package name prefix filter (e.g. com.example.app)"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val params = mutableMapOf<String, String>()
            getArgString(request.arguments, "package")?.let { params["package"] = it }
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/meta/classes", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "meta_methods",
                description = "Query method signatures declared in specified Class.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name (e.g. com.example.app.MainActivity)")
                    ),
                    required = listOf("apk_id", "class_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/meta/methods", mapOf("class_name" to className))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "meta_fields",
                description = "Query field declarations in specified Class.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name (e.g. com.example.app.MainActivity)")
                    ),
                    required = listOf("apk_id", "class_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/meta/fields", mapOf("class_name" to className))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "meta_main_activity",
                description = "Directly retrieve main activity class name and Java source code.",
                inputSchema = createToolSchema(
                    properties = mapOf("apk_id" to Pair("string", "Target apk_id UUID")),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val json = processManager.sendWorkerRequest(apkId, "/meta/main-activity")
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        // =================================================================
        // 3. On-Demand Decompilation Tools (decompile_*)
        // =================================================================

        server.addTool(
            Tool(
                name = "decompile_class",
                description = "Decompile Java source code on-demand for specified Class.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name (e.g. com.example.app.utils.CipherUtils)")
                    ),
                    required = listOf("apk_id", "class_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/decompile/java", mapOf("class_name" to className))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "decompile_smali",
                description = "Get Smali disassembly bytecode for specified Class.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name")
                    ),
                    required = listOf("apk_id", "class_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/decompile/smali", mapOf("class_name" to className))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "decompile_method",
                description = "Extract Java source code snippet for a specific Method in Class.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name"),
                        "method_name" to Pair("string", "Method name (e.g. onCreate or encryptAES)")
                    ),
                    required = listOf("apk_id", "class_name", "method_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val methodName = requireNotNull(getArgString(request.arguments, "method_name")) { "Missing required parameter 'method_name'" }
            val json = processManager.sendWorkerRequest(
                apkId,
                "/decompile/method",
                mapOf("class_name" to className, "method_name" to methodName)
            )
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        // =================================================================
        // 4. Resource File Tools (resource_*)
        // =================================================================

        server.addTool(
            Tool(
                name = "resource_strings",
                description = "Paginated extraction of strings.xml string table entries from APK.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val params = mutableMapOf<String, String>()
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/resource/strings", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "resource_list",
                description = "Paginated list of all resource file relative paths in APK (e.g. res/xml/...).",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val params = mutableMapOf<String, String>()
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/resource/list", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "resource_file",
                description = "Retrieve text content of specified resource file (e.g. network configs, layout XMLs).",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "file_name" to Pair("string", "Relative path to resource file (e.g. res/xml/network_security_config.xml)")
                    ),
                    required = listOf("apk_id", "file_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val fileName = requireNotNull(getArgString(request.arguments, "file_name")) { "Missing required parameter 'file_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/resource/file", mapOf("file_name" to fileName))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        // =================================================================
        // 5. Cross Reference and Search Tools (xref_* & search_*)
        // =================================================================

        server.addTool(
            Tool(
                name = "xref_class",
                description = "Find cross references to specified Class (usage sites and code snippets).",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id", "class_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val params = mutableMapOf("class_name" to className)
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/xref/class", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "xref_method",
                description = "Find call sites and code snippets for specified Method.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name"),
                        "method_name" to Pair("string", "Target method name"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id", "class_name", "method_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val methodName = requireNotNull(getArgString(request.arguments, "method_name")) { "Missing required parameter 'method_name'" }
            val params = mutableMapOf("class_name" to className, "method_name" to methodName)
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/xref/method", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "xref_field",
                description = "Find read/write reference code snippets for specified Field variable.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "class_name" to Pair("string", "Fully qualified class name"),
                        "field_name" to Pair("string", "Target field name"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id", "class_name", "field_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val className = requireNotNull(getArgString(request.arguments, "class_name")) { "Missing required parameter 'class_name'" }
            val fieldName = requireNotNull(getArgString(request.arguments, "field_name")) { "Missing required parameter 'field_name'" }
            val params = mutableMapOf("class_name" to className, "field_name" to fieldName)
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/xref/field", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "search_classes",
                description = "Global search for classes matching term in code, class names, method names or comments.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "search_term" to Pair("string", "Search keyword (e.g. AES/CBC or API endpoint)"),
                        "search_in" to Pair("string", "Search scope (code|class|method|field|comment), default: code"),
                        "package" to Pair("string", "Package name filter"),
                        "offset" to Pair("number", "Pagination offset (default: 0)"),
                        "count" to Pair("number", "Pagination count limit (default: 50)")
                    ),
                    required = listOf("apk_id", "search_term")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val searchTerm = requireNotNull(getArgString(request.arguments, "search_term")) { "Missing required parameter 'search_term'" }
            val params = mutableMapOf("search_term" to searchTerm)
            getArgString(request.arguments, "search_in")?.let { params["search_in"] = it }
            getArgString(request.arguments, "package")?.let { params["package"] = it }
            getArgInt(request.arguments, "offset")?.let { params["offset"] = it.toString() }
            getArgInt(request.arguments, "count")?.let { params["count"] = it.toString() }
            val json = processManager.sendWorkerRequest(apkId, "/search/classes", params)
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        server.addTool(
            Tool(
                name = "search_method",
                description = "Fuzzy match method name across all classes.",
                inputSchema = createToolSchema(
                    properties = mapOf(
                        "apk_id" to Pair("string", "Target apk_id UUID"),
                        "method_name" to Pair("string", "Method name keyword")
                    ),
                    required = listOf("apk_id", "method_name")
                )
            )
        ) { request ->
            val apkId = requireNotNull(getArgString(request.arguments, "apk_id")) { "Missing required parameter 'apk_id'" }
            val methodName = requireNotNull(getArgString(request.arguments, "method_name")) { "Missing required parameter 'method_name'" }
            val json = processManager.sendWorkerRequest(apkId, "/search/method", mapOf("method_name" to methodName))
            CallToolResult(content = listOf(TextContent(text = json)))
        }

        // =================================================================
        // 6. Connect and run in Stdio Transport listening mode
        // =================================================================

        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = mcpOutputStream.asSink().buffered()
        )
        server.createSession(transport)
        
        // Suspend main thread to keep MCP service online
        awaitCancellation()
    }
}
