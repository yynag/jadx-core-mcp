package com.yyang.jadx_server.worker

import com.yyang.jadx_server.server.JadxHttpServer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Worker：单 APK，业务 HTTP 仅 127.0.0.1。
 */
fun main(args: Array<String>) {
    var apkPath: String? = null
    var port = 0
    var masterPid: Long = -1
    var apkId = System.getenv("JADX_APK_ID") ?: "worker"
    var deobf = true

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--apk" -> if (i + 1 < args.size) { apkPath = args[++i] }
            "--port" -> if (i + 1 < args.size) { port = args[++i].toIntOrNull() ?: 0 }
            "--master-pid" -> if (i + 1 < args.size) { masterPid = args[++i].toLongOrNull() ?: -1 }
            "--apk-id" -> if (i + 1 < args.size) { apkId = args[++i] }
            "--deobf" -> if (i + 1 < args.size) { deobf = args[++i].toBooleanStrictOrNull() ?: true }
            "--bind" -> if (i + 1 < args.size) { i++ } // 忽略历史参数
        }
        i++
    }

    if (apkPath.isNullOrEmpty() || port <= 0) {
        System.err.println("[Worker] Requires --apk <path> and --port <port>")
        exitProcess(1)
    }

    if (masterPid > 0) {
        val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "master-heartbeat").apply { isDaemon = true }
        }
        heartbeatExecutor.scheduleAtFixedRate({
            val masterHandle = ProcessHandle.of(masterPid)
            if (masterHandle.isEmpty || !masterHandle.get().isAlive) {
                System.err.println("[Worker] Master PID $masterPid gone; exiting.")
                exitProcess(0)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    println("[Worker] apk=$apkPath port=$port apk_id=$apkId")

    try {
        val server = JadxHttpServer(port = port, cacheInstanceKey = apkId)
        server.engine.loadApk(apkPath, deobfuscationOn = deobf)
        server.start()
        Thread.currentThread().join()
    } catch (e: Exception) {
        System.err.println("[Worker] fatal:")
        e.printStackTrace()
        exitProcess(1)
    }
}
