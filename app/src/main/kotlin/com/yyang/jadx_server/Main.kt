package com.yyang.jadx_server

import com.yyang.jadx_server.mcp.JadxMcpServer
import com.yyang.jadx_server.server.JadxHttpServer
import com.yyang.jadx_server.server.JadxProcessManager
import org.slf4j.LoggerFactory
import org.slf4j.Logger.ROOT_LOGGER_NAME
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.ConsoleAppender
import kotlin.system.exitProcess

/**
 * Main entry point for Jadx Headless Server (Master Node Daemon).
 * 
 * Responsibilities:
 * 1. Parse command-line arguments (--apk, --port, --mcp, --help).
 * 2. Mode selection:
 *    - Default HTTP REST mode: Launch JDK HttpServer REST daemon (default port: 8650).
 *    - MCP Stdio mode (--mcp): Launch Model Context Protocol Stdio server for AI Agent integration.
 * 3. In MCP mode, redirect Logback console appenders to System.err to preserve System.out for MCP JSON-RPC protocol messages.
 */
fun main(args: Array<String>) {
    var apkPath: String? = null
    var port = 8650
    var isMcpMode = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--apk" -> {
                if (i + 1 < args.size) {
                    apkPath = args[i + 1]
                    i++
                }
            }
            "--port" -> {
                if (i + 1 < args.size) {
                    port = args[i + 1].toIntOrNull() ?: 8650
                    i++
                }
            }
            "--mcp" -> {
                isMcpMode = true
            }
            "-h", "--help" -> {
                printUsage()
                exitProcess(0)
            }
        }
        i++
    }

    if (isMcpMode) {
        // 1. Disable Logback internal StatusListener console output
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener")

        // 2. Capture true stdout for MCP communication, and redirect System.out to System.err
        val realStdout = System.out
        System.setOut(System.err)

        // 3. Redirect Logback ConsoleAppender to System.err
        redirectLogbackConsoleToStderr()
        System.err.println("Starting Jadx Server in MCP Stdio mode...")
        
        try {
            val processManager = JadxProcessManager()
            if (apkPath != null) {
                System.err.println("Loading initial APK for MCP mode: $apkPath")
                val result = processManager.loadApk(apkPath)
                System.err.println("Initial APK loaded successfully: $result")
            }
            val mcpServer = JadxMcpServer(processManager, mcpOutputStream = realStdout)
            mcpServer.start()
        } catch (e: Exception) {
            System.err.println("Failed to start MCP Server:")
            e.printStackTrace(System.err)
            exitProcess(1)
        }
    } else {
        // Default HTTP REST mode
        println("Starting Jadx Headless Server (REST mode)...")
        println("Port: $port")

        try {
            val server = JadxHttpServer(port)
            
            if (apkPath != null) {
                println("Loading initial APK path: $apkPath")
                server.processManager.loadApk(apkPath)
            } else {
                println("No initial APK provided. Waiting for /apk/load requests.")
            }
            
            server.start()
            
            Thread.currentThread().join()
        } catch (e: Exception) {
            System.err.println("Failed to start server:")
            e.printStackTrace()
            exitProcess(1)
        }
    }
}

/**
 * Redirect Logback ConsoleAppender target to System.err in MCP mode,
 * preventing log outputs from corrupting System.out MCP JSON-RPC protocol transport.
 */
private fun redirectLogbackConsoleToStderr() {
    try {
        val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME)
        if (rootLogger is Logger) {
            val consoleAppender = rootLogger.getAppender("CONSOLE") as? ConsoleAppender<*>
            if (consoleAppender != null) {
                consoleAppender.target = "System.err"
                consoleAppender.start()
            }
        }
    } catch (ignored: Exception) {
        // Fallback protection
    }
}

fun printUsage() {
    println("""
        Usage: jadx-core-mcp [--apk <apk-path>] [--port <port>] [--mcp]
        
        Options:
          --apk <path>    Path to Android APK file to load (optional, can be loaded via API/MCP tool).
          --port <port>   HTTP service port (default: 8650, effective in REST mode).
          --mcp           Run in Model Context Protocol (MCP) Stdio mode for AI Agents.
          -h, --help      Display this help message.
    """.trimIndent())
}
