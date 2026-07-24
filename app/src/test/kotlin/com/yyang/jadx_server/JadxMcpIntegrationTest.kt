package com.yyang.jadx_server

import com.google.gson.JsonParser
import com.yyang.jadx_server.server.JadxProcessManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Integration test suite for JADX MCP server and Worker sub-process communication.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JadxMcpIntegrationTest {

    companion object {
        private var testApkPath: String = ""
        private lateinit var processManager: JadxProcessManager
        private var apkId: String = ""

        @JvmStatic
        @BeforeAll
        fun setup() {
            processManager = JadxProcessManager()

            val envPath = System.getenv("JADX_TEST_APK")

            testApkPath = when {
                !envPath.isNullOrEmpty() && File(envPath).exists() -> envPath
                else -> createDummyTestZip().absolutePath
            }
            println("[McpTest] Using test APK path: $testApkPath")
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            if (apkId.isNotEmpty()) {
                try {
                    processManager.unloadApk(apkId, clearCache = true)
                } catch (e: Exception) {
                    // Ignore cleanup exceptions
                }
            }
        }

        private fun createDummyTestZip(): File {
            val tempFile = File.createTempFile("mcp_dummy_test_app", ".apk")
            tempFile.deleteOnExit()
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                val manifestContent = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.dummy.mcptest">
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
                        <string name="app_name">MCP Test App</string>
                        <string name="mcp_key">Hello MCP</string>
                    </resources>
                """.trimIndent()
                zos.write(stringsContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            return tempFile
        }
    }

    @Test
    @Order(1)
    fun testLoadApkForMcp() {
        val result = processManager.loadApk(testApkPath)
        assertEquals("success", result["status"])
        apkId = result["apk_id"].toString()
        assertTrue(apkId.isNotBlank())
        assertTrue(processManager.isWorkerAlive(apkId))
    }

    @Test
    @Order(2)
    fun testMcpWorkerManifestRequest() {
        val json = processManager.sendWorkerRequest(apkId, "/meta/manifest")
        val jsonObj = JsonParser.parseString(json).asJsonObject
        val content = jsonObj.get("content").asString
        assertTrue(content.contains("<manifest"))
    }

    @Test
    @Order(3)
    fun testMcpWorkerSummaryRequest() {
        val json = processManager.sendWorkerRequest(apkId, "/meta/summary")
        val jsonObj = JsonParser.parseString(json).asJsonObject
        assertTrue(jsonObj.has("classesCount"))
    }

    @Test
    @Order(4)
    fun testMcpWorkerStringsRequest() {
        val json = processManager.sendWorkerRequest(apkId, "/resource/strings", mapOf("offset" to "0", "count" to "10"))
        val jsonObj = JsonParser.parseString(json).asJsonObject
        assertTrue(jsonObj.has("strings"))
    }

    @Test
    @Order(5)
    fun testLoadApkWithCustomMaxHeap() {
        // 验证显示传递 maxHeap 参数（如 "1g"）时，ProcessManager 能够成功拉起 isolated Worker
        val result = processManager.loadApk(testApkPath, maxHeap = "1g")
        assertEquals("success", result["status"])
        val customApkId = result["apk_id"].toString()
        assertTrue(customApkId.isNotBlank())
        assertTrue(processManager.isWorkerAlive(customApkId))

        // 卸载回收该测试 Worker 进程
        val unloadRes = processManager.unloadApk(customApkId, clearCache = true)
        assertEquals("success", unloadRes["status"])
    }

    @Test
    @Order(6)
    fun testUnloadApkForMcp() {
        val result = processManager.unloadApk(apkId, clearCache = true)
        assertEquals("success", result["status"])
        assertFalse(processManager.isWorkerAlive(apkId))
    }
}
