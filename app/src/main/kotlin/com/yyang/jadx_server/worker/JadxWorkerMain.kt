package com.yyang.jadx_server.worker

import com.yyang.jadx_server.server.JadxHttpServer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Worker sub-process main entry point.
 * Each Worker process handles **a single target APK**.
 * When an APK is unloaded, the entire Worker sub-process is destroyed,
 * physically releasing 100% JVM heap and native handles with zero memory leaks.
 */
fun main(args: Array<String>) {
    var apkPath: String? = null
    var port = 0
    var masterPid: Long = -1

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
                    port = args[i + 1].toIntOrNull() ?: 0
                    i++
                }
            }
            "--master-pid" -> {
                if (i + 1 < args.size) {
                    masterPid = args[i + 1].toLongOrNull() ?: -1
                    i++
                }
            }
        }
        i++
    }

    if (apkPath.isNullOrEmpty() || port <= 0) {
        System.err.println("[Worker Process] Invalid arguments. Requires --apk <path> and --port <port>")
        exitProcess(1)
    }

    /**
     * Heartbeat check for orphan process protection.
     * If the Master process (parent PID) terminates unexpectedly, the Worker self-terminates to release ports and memory.
     */
    if (masterPid > 0) {
        val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutor.scheduleAtFixedRate({
            val masterHandle = ProcessHandle.of(masterPid)
            if (masterHandle.isEmpty || !masterHandle.get().isAlive) {
                System.err.println("[Worker Process] Master process (PID: $masterPid) has exited. Worker process is terminating...")
                exitProcess(0)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    println("[Worker Process] Starting isolated Worker process for APK: $apkPath, internal port: $port")

    try {
        val server = JadxHttpServer(port)
        server.engine.loadApk(apkPath)
        server.start()
        
        Thread.currentThread().join()
    } catch (e: Exception) {
        System.err.println("[Worker Process] Exception occurred in Worker process:")
        e.printStackTrace()
        exitProcess(1)
    }
}
