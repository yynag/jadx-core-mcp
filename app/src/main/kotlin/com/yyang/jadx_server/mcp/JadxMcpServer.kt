package com.yyang.jadx_server.mcp

import com.google.gson.GsonBuilder
import com.yyang.jadx_server.server.JadxProcessManager
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP tool 注册（Stdio 与 HTTP Streamable 共用同一套 tools）。
 *
 * WHY: OpenCode remote 需要 MCP-over-HTTP；local 仍用 Stdio。业务只维护一份 tool 表。
 * DECISION: createServer() 构建 Server；startStdio() / MasterKtor 分别挂传输层。
 */
class JadxMcpServer(
    private val processManager: JadxProcessManager
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private data class ToolDef(
        val name: String,
        val description: String,
        val properties: Map<String, Pair<String, String>>,
        val required: List<String>,
        val handler: (JsonObject?) -> String
    )

    private fun schema(properties: Map<String, Pair<String, String>>, required: List<String>): ToolSchema {
        val propsJson = buildJsonObject {
            properties.forEach { (name, typeAndDesc) ->
                putJsonObject(name) {
                    put("type", typeAndDesc.first)
                    put("description", typeAndDesc.second)
                }
            }
        }
        return ToolSchema(properties = propsJson, required = required)
    }

    private fun argString(args: JsonObject?, key: String): String? =
        args?.get(key)?.jsonPrimitive?.contentOrNull

    private fun argLong(args: JsonObject?, key: String): Long? {
        val p = args?.get(key)?.jsonPrimitive ?: return null
        p.doubleOrNull?.let { return it.toLong() }
        return p.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun argInt(args: JsonObject?, key: String): Int? = argLong(args, key)?.toInt()

    private fun argBool(args: JsonObject?, key: String): Boolean? {
        val p = args?.get(key)?.jsonPrimitive ?: return null
        p.booleanOrNull?.let { return it }
        return p.contentOrNull?.toBooleanStrictOrNull()
    }

    private fun requireArg(args: JsonObject?, key: String): String =
        requireNotNull(argString(args, key)) { "Missing required parameter '$key'" }

    private fun workerGet(
        apkId: String,
        path: String,
        params: MutableMap<String, String> = mutableMapOf(),
        timeoutSec: Long = 120
    ): String {
        val t = params["timeout"]?.toLongOrNull()?.let { it + 5 } ?: timeoutSec
        return processManager.sendWorkerRequest(apkId, path, params, timeoutSec = t.coerceIn(5, 600))
    }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text = text)), isError = false)
    private fun err(message: String) = CallToolResult(content = listOf(TextContent(text = message)), isError = true)

    private fun buildTools(): List<ToolDef> {
        val apkIdP = "apk_id" to ("string" to "Target apk_id UUID from apk_load")
        val timeoutP = "timeout" to ("number" to "Per-call timeout seconds (Agent decides). Server hard-cap applies.")
        val offsetP = "offset" to ("number" to "Pagination offset (default 0)")
        val countP = "count" to ("number" to "Page size (default 50)")
        val maxScanP = "max_scan" to ("number" to "Max classes to scan for code/comment (default 500). class/method/field ignore this and scan all.")
        val maxDecompP = "max_decompile" to ("number" to "Max on-demand decompiles for scope=code (default 50)")

        return listOf(
            ToolDef(
                name = "apk_load",
                description = "Load APK into isolated Worker JVM; returns apk_id. Then: meta_summary → search (string/class) → xref → decompile (prefer target=method, timeout=90 for big classes).",
                properties = mapOf(
                    "apk_path" to ("string" to "Absolute path to APK/DEX/JAR on the server host"),
                    "max_heap" to ("string" to "Optional Worker heap e.g. 4g (Agent may set per APK size)"),
                    "deobf" to ("boolean" to "Enable JADX deobfuscation (default true, matches GUI unique names)")
                ),
                required = listOf("apk_path"),
                handler = { args ->
                    gson.toJson(
                        processManager.loadApk(
                            requireArg(args, "apk_path"),
                            argString(args, "max_heap"),
                            argBool(args, "deobf") ?: true
                        )
                    )
                }
            ),
            ToolDef(
                name = "apk_unload",
                description = "Destroy Worker for apk_id and reclaim heap. clear_cache deletes disk decompile cache.",
                properties = mapOf(
                    apkIdP,
                    "clear_cache" to ("boolean" to "Delete disk cache (default false)")
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    gson.toJson(processManager.unloadApk(requireArg(args, "apk_id"), argBool(args, "clear_cache") ?: false))
                }
            ),
            ToolDef(
                name = "apk_list",
                description = "List active apk_id workers.",
                properties = emptyMap(),
                required = emptyList(),
                handler = { gson.toJson(mapOf("apks" to processManager.listApks())) }
            ),
            ToolDef(
                name = "meta_summary",
                description = "APK summary: classesCount, mainActivity, mainPackage, deobf, topPackages. Start here after apk_load.",
                properties = mapOf(apkIdP),
                required = listOf("apk_id"),
                handler = { args -> workerGet(requireArg(args, "apk_id"), "/meta/summary") }
            ),
            ToolDef(
                name = "meta_manifest",
                description = "Decoded AndroidManifest.xml, or component list when component_type is set (activity|service|receiver|provider).",
                properties = mapOf(
                    apkIdP,
                    "component_type" to ("string" to "activity | service | receiver | provider; omit for full XML"),
                    "only_exported" to ("boolean" to "When listing components, keep exported only (default false)")
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val type = argString(args, "component_type")
                    if (!type.isNullOrBlank()) {
                        val params = mutableMapOf("component_type" to type)
                        argBool(args, "only_exported")?.let { params["only_exported"] = it.toString() }
                        workerGet(apkId, "/meta/components", params)
                    } else {
                        workerGet(apkId, "/meta/manifest")
                    }
                }
            ),
            ToolDef(
                name = "meta_class",
                description = "Without class_name: paginated FQCN list (package or main_app=true). With class_name: methods+fields with signatures.",
                properties = mapOf(
                    apkIdP,
                    "class_name" to ("string" to "FQCN (also accepts p000./defpackage. aliases)"),
                    "package" to ("string" to "Package filter when listing"),
                    "main_app" to ("boolean" to "List classes in manifest package"),
                    offsetP, countP
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val className = argString(args, "class_name")
                    if (!className.isNullOrBlank()) {
                        val methods = workerGet(apkId, "/meta/methods", mutableMapOf("class_name" to className))
                        val fields = workerGet(apkId, "/meta/fields", mutableMapOf("class_name" to className))
                        val m = gson.fromJson(methods, Map::class.java)
                        val f = gson.fromJson(fields, Map::class.java)
                        gson.toJson(mapOf("class_name" to (m["class_name"] ?: className), "methods" to m["methods"], "fields" to f["fields"]))
                    } else {
                        val params = mutableMapOf<String, String>()
                        argString(args, "package")?.let { params["package"] = it }
                        argBool(args, "main_app")?.let { params["main_app"] = it.toString() }
                        argInt(args, "offset")?.let { params["offset"] = it.toString() }
                        argInt(args, "count")?.let { params["count"] = it.toString() }
                        workerGet(apkId, "/meta/classes", params)
                    }
                }
            ),
            ToolDef(
                name = "decompile",
                description = "On-demand decompile. target=java|smali|method|main_activity. Prefer method. Large class: timeout=90. Output truncated at 80k chars.",
                properties = mapOf(
                    apkIdP,
                    "target" to ("string" to "java | smali | method | main_activity (default java)"),
                    "class_name" to ("string" to "FQCN (required except main_activity)"),
                    "method_name" to ("string" to "Required when target=method"),
                    timeoutP
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val target = (argString(args, "target") ?: "java").lowercase()
                    val params = mutableMapOf<String, String>()
                    argLong(args, "timeout")?.let { params["timeout"] = it.toString() }
                    when (target) {
                        "java" -> {
                            params["class_name"] = requireArg(args, "class_name")
                            workerGet(apkId, "/decompile/java", params)
                        }
                        "smali" -> {
                            params["class_name"] = requireArg(args, "class_name")
                            workerGet(apkId, "/decompile/smali", params)
                        }
                        "method" -> {
                            params["class_name"] = requireArg(args, "class_name")
                            params["method_name"] = requireArg(args, "method_name")
                            workerGet(apkId, "/decompile/method", params)
                        }
                        "main_activity" -> workerGet(apkId, "/meta/main-activity", params)
                        else -> throw IllegalArgumentException("target must be java|smali|method|main_activity")
                    }
                }
            ),
            ToolDef(
                name = "resource",
                description = "action=list|file|strings|id. id looks up 0x7f... resource names.",
                properties = mapOf(
                    apkIdP,
                    "action" to ("string" to "list | file | strings | id (default list)"),
                    "file_name" to ("string" to "For action=file"),
                    "id" to ("string" to "For action=id, e.g. 0x7f140000"),
                    offsetP, countP
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val action = (argString(args, "action") ?: "list").lowercase()
                    val params = mutableMapOf<String, String>()
                    argInt(args, "offset")?.let { params["offset"] = it.toString() }
                    argInt(args, "count")?.let { params["count"] = it.toString() }
                    when (action) {
                        "list" -> workerGet(apkId, "/resource/list", params)
                        "strings" -> workerGet(apkId, "/resource/strings", params)
                        "file" -> {
                            params["file_name"] = requireArg(args, "file_name")
                            workerGet(apkId, "/resource/file", params)
                        }
                        "id" -> {
                            params["id"] = argString(args, "id") ?: requireArg(args, "file_name")
                            workerGet(apkId, "/resource/id", params)
                        }
                        else -> throw IllegalArgumentException("action must be list|file|strings|id")
                    }
                }
            ),
            ToolDef(
                name = "xref",
                description = "Cross-references. Returns class_name, method, code_snippet; method_code when small. Field snippets skip declarations.",
                properties = mapOf(
                    apkIdP,
                    "target_type" to ("string" to "class | method | field"),
                    "class_name" to ("string" to "FQCN"),
                    "method_name" to ("string" to "For method"),
                    "field_name" to ("string" to "For field"),
                    offsetP, countP, timeoutP
                ),
                required = listOf("apk_id", "target_type", "class_name"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val type = requireArg(args, "target_type").lowercase()
                    val params = mutableMapOf("class_name" to requireArg(args, "class_name"))
                    argInt(args, "offset")?.let { params["offset"] = it.toString() }
                    argInt(args, "count")?.let { params["count"] = it.toString() }
                    argLong(args, "timeout")?.let { params["timeout"] = it.toString() }
                    when (type) {
                        "class" -> workerGet(apkId, "/xref/class", params)
                        "method" -> {
                            params["method_name"] = requireArg(args, "method_name")
                            workerGet(apkId, "/xref/method", params)
                        }
                        "field" -> {
                            params["field_name"] = requireArg(args, "field_name")
                            workerGet(apkId, "/xref/field", params)
                        }
                        else -> throw IllegalArgumentException("target_type must be class|method|field")
                    }
                }
            ),
            ToolDef(
                name = "search",
                description = "scope=class|method_name|field|string|code|comment. Prefer string (DEX const, fast) then class. Hits include preview line. code/comment are budgeted.",
                properties = mapOf(
                    apkIdP,
                    "scope" to ("string" to "class | string | code | method_name | field | comment (default class)"),
                    "search_term" to ("string" to "Keyword"),
                    "method_name" to ("string" to "For scope=method_name"),
                    "package" to ("string" to "Optional package filter"),
                    "search_in" to ("string" to "Alias of scope"),
                    maxScanP, maxDecompP,
                    offsetP, countP, timeoutP
                ),
                required = listOf("apk_id"),
                handler = { args ->
                    val apkId = requireArg(args, "apk_id")
                    val scope = (argString(args, "scope") ?: argString(args, "search_in") ?: "class").lowercase()
                    val params = mutableMapOf<String, String>()
                    argInt(args, "offset")?.let { params["offset"] = it.toString() }
                    argInt(args, "count")?.let { params["count"] = it.toString() }
                    argLong(args, "timeout")?.let { params["timeout"] = it.toString() }
                    argInt(args, "max_scan")?.let { params["max_scan"] = it.toString() }
                    argInt(args, "max_decompile")?.let { params["max_decompile"] = it.toString() }
                    argString(args, "package")?.let { params["package"] = it }
                    when (scope) {
                        "method_name", "method" -> {
                            params["method_name"] = argString(args, "method_name")
                                ?: argString(args, "search_term")
                                ?: throw IllegalArgumentException("method_name or search_term required")
                            workerGet(apkId, "/search/method", params)
                        }
                        "field" -> {
                            params["search_term"] = requireArg(args, "search_term")
                            workerGet(apkId, "/search/field", params)
                        }
                        "string" -> {
                            params["search_term"] = requireArg(args, "search_term")
                            workerGet(apkId, "/search/string", params, timeoutSec = 180)
                        }
                        "class", "code", "comment" -> {
                            params["search_term"] = requireArg(args, "search_term")
                            params["search_in"] = scope
                            workerGet(apkId, "/search/classes", params)
                        }
                        else -> throw IllegalArgumentException("scope must be class|string|code|method_name|field|comment")
                    }
                }
            ),
            ToolDef(
                name = "rename",
                description = "Rename class/method/field alias. Persisted next to the APK cache and re-applied on next apk_load of the same file.",
                properties = mapOf(
                    apkIdP,
                    "target_type" to ("string" to "class | method | field"),
                    "class_name" to ("string" to "FQCN"),
                    "name" to ("string" to "Current method/field name; ignored for class"),
                    "new_name" to ("string" to "New alias")
                ),
                required = listOf("apk_id", "target_type", "class_name", "new_name"),
                handler = { args ->
                    val params = mutableMapOf(
                        "target_type" to requireArg(args, "target_type"),
                        "class_name" to requireArg(args, "class_name"),
                        "new_name" to requireArg(args, "new_name")
                    )
                    argString(args, "name")?.let { params["name"] = it }
                    workerGet(requireArg(args, "apk_id"), "/rename", params)
                }
            )
        )
    }

    /** 构建已注册 tools 的 MCP Server 实例（每次会话可新建） */
    fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "jadx-core-mcp", version = "1.4.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
        )
        for (def in buildTools()) {
            server.addTool(
                Tool(name = def.name, description = def.description, inputSchema = schema(def.properties, def.required))
            ) { request ->
                try {
                    ok(def.handler(request.arguments))
                } catch (e: Exception) {
                    err(e.message ?: e::class.java.simpleName)
                }
            }
        }
        return server
    }

    /** 本机 OpenCode type=local：Stdio 传输 */
    fun startStdio(mcpOutputStream: java.io.OutputStream = System.out): Unit = runBlocking {
        val server = createServer()
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = mcpOutputStream.asSink().buffered()
        )
        server.createSession(transport)
        awaitCancellation()
    }
}
