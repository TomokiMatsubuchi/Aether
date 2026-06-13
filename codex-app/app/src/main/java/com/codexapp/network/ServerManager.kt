package com.codexapp.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.codexapp.service.CodexServerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL

import com.codexapp.R

class ServerManager(private val context: Context) {
    val isReady = MutableStateFlow(false)
    val statusMessage = MutableStateFlow(context.getString(R.string.starting_server))

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
    private var startupJob: Job? = null

    @Synchronized
    fun start() {
        if (startupJob?.isActive == true) {
            Log.i("ServerManager", "Startup is already in progress, skipping start call.")
            return
        }
        startupJob = scope.launch {
            try {
                delay(5000)
                Log.i("ServerManager", "Checking if Node.js server on port 3000 is already running...")
                if (isServerRunning(3000)) {
                    Log.i("ServerManager", "Node.js server is already running, skipping startup.")
                    prefs.edit().putString("server_url", "http://127.0.0.1:3000").apply()
                    isReady.value = true
                    statusMessage.value = context.getString(R.string.server_ready)
                    return@launch
                }

                Log.i("ServerManager", "Node.js server not running. Launching via Termux...")
                statusMessage.value = context.getString(R.string.starting_node_server)

                try {
                    startNodeServerViaTermux()
                    Log.i("ServerManager", "Waiting for Node.js server on port 3000 to be ready...")
                    waitForServer(3000, 15000)
                    Log.i("ServerManager", "Node.js server started successfully on port 3000!")
                    prefs.edit().putString("server_url", "http://127.0.0.1:3000").apply()
                    isReady.value = true
                    statusMessage.value = context.getString(R.string.server_ready)
                } catch (e: Exception) {
                    Log.w("ServerManager", "Failed to start Node.js server on port 3000: ${e.message}. Starting fallback server...")
                    statusMessage.value = context.getString(R.string.starting_fallback_server)

                    // Fallback to internal Kotlin server
                    CodexServerService.startService(context)

                    Log.i("ServerManager", "Waiting for fallback server on port 3001 to be ready...")
                    waitForServer(3001, 15000)
                    Log.i("ServerManager", "Fallback server started successfully on port 3001!")
                    prefs.edit().putString("server_url", "http://127.0.0.1:3001").apply()
                    isReady.value = true
                    statusMessage.value = context.getString(R.string.server_ready_fallback)
                }
            } catch (e: Exception) {
                Log.e("ServerManager", "Server startup failed: ${e.message}", e)
                isReady.value = false
                statusMessage.value = context.getString(R.string.server_error, e.message ?: "Unknown error")
            }
        }
    }

    private fun startNodeServerViaTermux() {
        try {
            Log.i("ServerManager", "Sending intent to Termux (RunCommandService) to start server in Ubuntu container...")
            val intent = Intent().apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf(
                        "-c",
                        "pkill node || true; sleep 1; /data/data/com.termux/files/usr/bin/proot-distro login --no-kill-on-exit ubuntu -- bash -c 'source ~/.bashrc && cp /sdcard/Download/index.js /root/workspace/session_apps/codex-app/server/index.js || true && cd /root/workspace/session_apps/codex-app/server && exec node index.js >> /sdcard/Download/codex_server.log 2>&1'"
                    )
                )
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            }
            context.startService(intent)
            Log.i("ServerManager", "Termux RUN_COMMAND intent sent successfully.")
        } catch (e: SecurityException) {
            Log.e("ServerManager", "Termux RUN_COMMAND permission denied. Please grant it in App Settings.", e)
            throw e
        } catch (e: Exception) {
            Log.e("ServerManager", "Failed to start Node.js server via Termux: ${e.message}", e)
            throw e
        }
    }

    private fun isServerRunning(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForServer(port: Int, timeoutMs: Long) {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (isServerRunning(port)) {
                    Log.i("ServerManager", "Server on port $port responded with 200")
                    return@withContext
                }
                delay(500)
            }
            throw Exception("Server on port $port did not start within ${timeoutMs / 1000}s")
        }
    }
}
