package com.yyang.jadx_server

import com.google.gson.JsonParser
import com.yyang.jadx_server.server.JadxProcessManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 进程池路径测试（MCP 与 REST 共用 ProcessManager）。
 * 完整 MCP Stdio 会话需外部客户端；此处验证 load/decompile/search 业务与错误契约。
 */
class JadxMcpIntegrationTest {

    companion object {
        private lateinit var pm: JadxProcessManager
        private lateinit var artifact: String
        private var apkId: String = ""

        @JvmStatic
        @BeforeAll
        fun setup() {
            pm = JadxProcessManager()
            val resource = JadxMcpIntegrationTest::class.java.classLoader.getResource("sample.jar")
                ?: error("sample.jar missing")
            artifact = File(resource.toURI()).absolutePath
            val result = pm.loadApk(artifact, maxHeap = "512m")
            apkId = result["apk_id"] as String
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            try {
                if (apkId.isNotEmpty()) pm.unloadApk(apkId, clearCache = true)
            } catch (_: Exception) {
            }
            pm.shutdownAll()
        }
    }

    @Test
    fun testWorkerSummaryAndDecompile() {
        val summary = pm.sendWorkerRequest(apkId, "/meta/summary")
        val s = JsonParser.parseString(summary).asJsonObject
        assertTrue(s.get("classesCount").asInt >= 1)

        val decompiled = pm.sendWorkerRequest(
            apkId,
            "/decompile/java",
            mapOf("class_name" to "com.yyang.sample.HelloSample")
        )
        val code = JsonParser.parseString(decompiled).asJsonObject.get("code").asString
        assertTrue(code.contains("HelloSample"))
    }

    @Test
    fun testSearchBudgetFieldsPresent() {
        val json = pm.sendWorkerRequest(
            apkId,
            "/search/classes",
            mapOf("search_term" to "Hello", "search_in" to "class")
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("limits"))
        assertTrue(obj.getAsJsonArray("classes").size() >= 1)
    }

    @Test
    fun testInvalidSearchThrows() {
        var threw = false
        try {
            pm.sendWorkerRequest(
                apkId,
                "/search/classes",
                mapOf("search_term" to "x", "search_in" to "bogus")
            )
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message!!.contains("400") || e.message!!.contains("Unsupported") || e.message!!.contains("error"))
        }
        assertTrue(threw, "invalid search_in must fail")
    }
}
