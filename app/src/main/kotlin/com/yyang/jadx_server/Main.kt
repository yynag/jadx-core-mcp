package com.yyang.jadx_server

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.ConsoleAppender
import com.yyang.jadx_server.mcp.JadxMcpServer
import com.yyang.jadx_server.server.JadxProcessManager
import com.yyang.jadx_server.server.MasterKtorServer
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * 入口：
 * - 默认：Master Ktor = REST + MCP HTTP `/mcp`（局域网 OpenCode remote）
 * - `--mcp`：纯 Stdio（本机 OpenCode local）
 */
fun main(args: Array<String>) {
    var apkPath: String? = null
    var port = 8650
    var isMcpStdio = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--apk" -> if (i + 1 < args.size) { apkPath = args[++i] }
            "--port" -> if (i + 1 < args.size) { port = args[++i].toIntOrNull() ?: 8650 }
            "--mcp" -> isMcpStdio = true
            "--bind" -> if (i + 1 < args.size) { i++ } // 忽略：固定 0.0.0.0
            "-h", "--help" -> {
                printUsage()
                exitProcess(0)
            }
        }
        i++
    }

    if (isMcpStdio) {
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener")
        val realStdout = System.out
        System.setOut(System.err)
        redirectLogbackConsoleToStderr()
        System.err.println("MCP Stdio mode (OpenCode type=local)...")
        try {
            val pm = JadxProcessManager()
            if (apkPath != null) {
                System.err.println("Preload: $apkPath -> ${pm.loadApk(apkPath)}")
            }
            JadxMcpServer(pm).startStdio(mcpOutputStream = realStdout)
        } catch (e: Exception) {
            System.err.println("MCP Stdio failed:")
            e.printStackTrace(System.err)
            exitProcess(1)
        }
    } else {
        println("Starting Master (REST + MCP HTTP) on 0.0.0.0:$port")
        try {
            val master = MasterKtorServer(port = port)
            if (apkPath != null) {
                println("Preload: $apkPath")
                println(master.processManager().loadApk(apkPath))
            }
            master.start()
        } catch (e: Exception) {
            System.err.println("Master failed:")
            e.printStackTrace()
            exitProcess(1)
        }
    }
}

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
    } catch (_: Exception) {
    }
}

fun printUsage() {
    println(
        """
        Usage: jadx-core-mcp [--apk <path>] [--port <port>] [--mcp]

        Modes:
          (default)  REST + MCP Streamable HTTP on 0.0.0.0:<port>
                     MCP URL: http://<host>:<port>/mcp   (OpenCode type=remote)
          --mcp      MCP Stdio only                      (OpenCode type=local)

        Options:
          --apk <path>   Optional preload
          --port <port>  Default 8650 (default mode only)
          -h, --help

        Deploy env (optional):
          JADX_WORKER_XMX           Default Worker heap if max_heap omitted (e.g. 4g)
          JADX_WORKER_TIMEOUT_SEC   APK load ready timeout seconds
          JADX_DECOMPILE_TIMEOUT    Default per-class decompile seconds (request timeout overrides)

        No auth token. Bind fixed 0.0.0.0 for LAN. Trusted network only.
        """.trimIndent()
    )
}
