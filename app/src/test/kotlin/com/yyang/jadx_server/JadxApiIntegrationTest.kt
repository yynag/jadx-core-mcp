package com.yyang.jadx_server

import com.google.gson.JsonParser
import com.yyang.jadx_server.server.MasterKtorServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JadxApiIntegrationTest {

    companion object {
        private const val PORT = 8652
        private const val BASE_URL = "http://127.0.0.1:$PORT"
        private lateinit var server: MasterKtorServer
        private lateinit var testArtifact: String
        private var apkId1: String = ""
        private var apkId2: String = ""

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build()

        @JvmStatic
        @BeforeAll
        fun setup() {
            server = MasterKtorServer(port = PORT)
            server.start(wait = false)
            Thread.sleep(1500)

            val envPath = System.getenv("JADX_TEST_APK")
            testArtifact = when {
                !envPath.isNullOrEmpty() && File(envPath).exists() -> envPath
                else -> {
                    val resource = JadxApiIntegrationTest::class.java.classLoader.getResource("sample.jar")
                        ?: error("sample.jar missing from test resources")
                    File(resource.toURI()).absolutePath
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            try {
                if (apkId1.isNotEmpty()) server.processManager().unloadApk(apkId1, clearCache = true)
            } catch (_: Exception) {
            }
            try {
                if (apkId2.isNotEmpty()) server.processManager().unloadApk(apkId2, clearCache = true)
            } catch (_: Exception) {
            }
            server.stop()
        }

        fun sendRequest(
            endpoint: String,
            method: String = "GET",
            jsonBody: String? = null,
            expectedStatus: Int = 200
        ): String {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(120))
            if (method == "POST" && jsonBody != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                builder.header("Content-Type", "application/json")
            } else {
                builder.GET()
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(
                expectedStatus,
                response.statusCode(),
                "Endpoint $endpoint expected $expectedStatus got ${response.statusCode()} body=${response.body()}"
            )
            return response.body()
        }
    }

    @Test
    @Order(1)
    fun testHealthBeforeLoad() {
        val json = JsonParser.parseString(sendRequest("/health")).asJsonObject
        assertEquals("ok", json.get("status").asString)
        assertEquals(0, json.get("activeApksCount").asInt)
        assertEquals("/mcp", json.get("mcp").asString)
    }

    @Test
    @Order(2)
    fun testLoadTwoInstances() {
        val body = """{"apk_path":"$testArtifact","max_heap":"512m"}"""
        val r1 = JsonParser.parseString(sendRequest("/apk/load", "POST", body)).asJsonObject
        assertEquals("success", r1.get("status").asString)
        apkId1 = r1.get("apk_id").asString
        assertTrue(apkId1.isNotBlank())

        val r2 = JsonParser.parseString(sendRequest("/apk/load", "POST", body)).asJsonObject
        apkId2 = r2.get("apk_id").asString
        assertNotEquals(apkId1, apkId2)
    }

    @Test
    @Order(3)
    fun testSummaryHasClasses() {
        val json = JsonParser.parseString(sendRequest("/meta/summary?apk_id=$apkId1")).asJsonObject
        assertTrue(json.get("classesCount").asInt >= 1)
    }

    @Test
    @Order(4)
    fun testDecompileRealSource() {
        val path = "/decompile/java?apk_id=$apkId1&class_name=com.yyang.sample.HelloSample"
        val json = JsonParser.parseString(sendRequest(path)).asJsonObject
        val code = json.get("code").asString
        assertTrue(code.contains("HelloSample"))
        assertTrue(code.contains("JADX_CORE_MCP_SAMPLE_MAGIC") || code.contains("greet"))
    }

    @Test
    @Order(5)
    fun testSearchClassDefault() {
        val path = "/search/classes?apk_id=$apkId1&search_term=HelloSample&search_in=class"
        val json = JsonParser.parseString(sendRequest(path)).asJsonObject
        val classes = json.getAsJsonArray("classes")
        assertTrue(classes.size() >= 1)
        assertTrue(classes[0].asJsonObject.get("class_name").asString.contains("HelloSample"))
        assertFalse(classes[0].asJsonObject.has("code"))
    }

    @Test
    @Order(6)
    fun testSearchInvalidScopeErrors() {
        val path = "/search/classes?apk_id=$apkId1&search_term=x&search_in=method"
        val body = sendRequest(path, expectedStatus = 400)
        assertEquals("error", JsonParser.parseString(body).asJsonObject.get("status").asString)
    }

    @Test
    @Order(7)
    fun testMissingAndInvalidApkId() {
        sendRequest("/meta/manifest", expectedStatus = 400)
        sendRequest("/meta/manifest?apk_id=non_existent_uuid", expectedStatus = 404)
    }

    @Test
    @Order(8)
    fun testClassNotFoundIsError() {
        val path = "/decompile/java?apk_id=$apkId1&class_name=com.no.such.Class"
        val body = sendRequest(path, expectedStatus = 404)
        assertTrue(body.contains("error") || body.contains("NOT_FOUND") || body.contains("not found"))
    }

    @Test
    @Order(9)
    fun testUnload() {
        val body = sendRequest("/apk/unload", "POST", """{"apk_id":"$apkId1"}""")
        assertEquals("success", JsonParser.parseString(body).asJsonObject.get("status").asString)
        apkId1 = ""
        val list = JsonParser.parseString(sendRequest("/apk/list")).asJsonObject.getAsJsonArray("apks")
        assertEquals(1, list.size())
    }
}
