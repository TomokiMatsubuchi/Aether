package com.codexapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.codexapp.network.CodexClient
import com.codexapp.network.ServerManager
import com.codexapp.ui.theme.CodexTheme
import com.codexapp.ui.CodexNavHost

class MainActivity : ComponentActivity() {
    private lateinit var codexClient: CodexClient
    private lateinit var serverManager: ServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            permissions.add("com.termux.permission.RUN_COMMAND")
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 101)
        }

        serverManager = ServerManager(applicationContext)
        serverManager.start()
        codexClient = CodexClient(applicationContext)

        intent?.let { handleIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        if (::serverManager.isInitialized) {
            serverManager.start()
        }

        setContent {
            CodexTheme {
                CodexNavHost(serverManager = serverManager, codexClient = codexClient)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val sessionId = intent.getStringExtra("SESSION_ID")
        if (sessionId != null) {
            codexClient.switchSession(sessionId)
        }
    }
}
