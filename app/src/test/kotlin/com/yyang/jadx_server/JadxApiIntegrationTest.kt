package com.yyang.jadx_server

import com.google.gson.JsonParser
import com.yyang.jadx_server.server.JadxHttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * REST API Integration Test suite (supporting multi-APK concurrency and UUID apk_id binding).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JadxApiIntegrationTest {

    companion object {
        private var testApkPath: String = ""
        private const val PORT = 8652
        private const val BASE_URL = "http://127.0.0.1:8652"

        private lateinit var server: JadxHttpServer
        private var apkId1: String = ""
        private var apkId2: String = ""

        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build()

        @JvmStatic
        @BeforeAll
        fun setup() {
            println("Starting test server on port $PORT...")
            server = JadxHttpServer(PORT)
            server.start()

            val envPath = System.getenv("JADX_TEST_APK")
            
            testApkPath = when {
                !envPath.isNullOrEmpty() && File(envPath).exists() -> envPath
                else -> createDummyTestZip().absolutePath
            }
            println("Using test APK/JAR path: $testApkPath")
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            println("Cleaning up all Worker processes...")
            if (apkId1.isNotEmpty()) {
                try { server.processManager.unloadApk(apkId1, clearCache = true) } catch (e: Exception) {}
            }
            if (apkId2.isNotEmpty()) {
                try { server.processManager.unloadApk(apkId2, clearCache = true) } catch (e: Exception) {}
            }
        }

        private fun createDummyTestZip(): File {
            val tempFile = File.createTempFile("dummy_test_app", ".apk")
            tempFile.deleteOnExit()
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                val manifestContent = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.dummy.testapp">
                        <application android:name=".MainApp">
                            <activity android:name=".MainActivity">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                """.trimIndent()
                zos.write(manifestContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("res/values/strings.xml"))
                val stringsContent = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="app_name">Dummy Test App</string>
                        <string name="welcome">Hello World</string>
                    </resources>
                """.trimIndent()
                zos.write(stringsContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            return tempFile
        }

        fun sendRequest(endpoint: String, method: String = "GET", jsonBody: String? = null, expectedStatus: Int = 200): String {
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
            assertEquals(expectedStatus, response.statusCode(), "Endpoint $endpoint status code mismatch! Expected $expectedStatus, actual ${response.statusCode()}")
            return response.body()
        }
    }

    @Test
    @Order(1)
    fun testHealthBeforeLoad() {
        val json = sendRequest("/health")
        val jsonObj = JsonParser.parseString(json).asJsonObject
        assertEquals("ok", jsonObj.get("status").asString)
        assertEquals(0, jsonObj.get("activeApksCount").asInt)
    }

    @Test
    @Order(2)
    fun testLoadTwoApks() {
        // Load first APK
        val postJson = "{\"apk_path\":\"$testApkPath\"}"
        val resp1 = sendRequest("/apk/load", method = "POST", jsonBody = postJson)
        val jsonObj1 = JsonParser.parseString(resp1).asJsonObject
        assertEquals("success", jsonObj1.get("status").asString)
        apkId1 = jsonObj1.get("apk_id").asString
        assertTrue(apkId1.isNotBlank())

        // Load second APK
        val resp2 = sendRequest("/apk/load", method = "POST", jsonBody = postJson)
        val jsonObj2 = JsonParser.parseString(resp2).asJsonObject
        assertEquals("success", jsonObj2.get("status").asString)
        apkId2 = jsonObj2.get("apk_id").asString
        assertTrue(apkId2.isNotBlank())

        assertNotEquals(apkId1, apkId2, "Generated apk_ids across multiple loads should be unique")
    }

    @Test
    @Order(3)
    fun testApkListAndHealthAfterLoad() {
        val listJson = sendRequest("/apk/list")
        val listObj = JsonParser.parseString(listJson).asJsonObject
        val apks = listObj.get("apks").asJsonArray
        assertEquals(2, apks.size())

        val healthJson = sendRequest("/health")
        val healthObj = JsonParser.parseString(healthJson).asJsonObject
        assertEquals(2, healthObj.get("activeApksCount").asInt)
    }

    @Test
    @Order(4)
    fun testMissingApkIdInterception() {
        // Missing apk_id, expect HTTP 400
        val body = sendRequest("/meta/manifest", expectedStatus = 400)
        val jsonObj = JsonParser.parseString(body).asJsonObject
        assertEquals("error", jsonObj.get("status").asString)
        assertTrue(jsonObj.get("message").asString.contains("apk_id"))
    }

    @Test
    @Order(5)
    fun testInvalidApkIdInterception() {
        // Invalid apk_id, expect HTTP 404
        val body = sendRequest("/meta/manifest?apk_id=non_existent_uuid", expectedStatus = 404)
        val jsonObj = JsonParser.parseString(body).asJsonObject
        assertEquals("error", jsonObj.get("status").asString)
    }

    @Test
    @Order(6)
    fun testManifestDecodeWithValidApkId() {
        val json = sendRequest("/meta/manifest?apk_id=$apkId1")
        val jsonObj = JsonParser.parseString(json).asJsonObject
        val content = jsonObj.get("content").asString
        assertTrue(content.contains("<manifest"))
    }

    @Test
    @Order(7)
    fun testClassesAndResourcesWithValidApkId() {
        val classesJson = sendRequest("/meta/classes?apk_id=$apkId2&offset=0&count=5")
        val classesObj = JsonParser.parseString(classesJson).asJsonObject
        assertNotNull(classesObj.get("classes"))

        val stringsJson = sendRequest("/resource/strings?apk_id=$apkId1&offset=0&count=2")
        val stringsArr = JsonParser.parseString(stringsJson).asJsonObject.get("strings").asJsonArray
        assertNotNull(stringsArr)
    }

    @Test
    @Order(8)
    fun testUnloadApk() {
        val unloadJson = sendRequest("/apk/unload", method = "POST", jsonBody = "{\"apk_id\":\"$apkId1\"}")
        val jsonObj = JsonParser.parseString(unloadJson).asJsonObject
        assertEquals("success", jsonObj.get("status").asString)

        val listJson = sendRequest("/apk/list")
        val listObj = JsonParser.parseString(listJson).asJsonObject
        val apks = listObj.get("apks").asJsonArray
        assertEquals(1, apks.size())
    }
}
