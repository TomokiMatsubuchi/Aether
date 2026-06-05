package com.codexapp.network

import android.content.Context
import com.codexapp.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import com.codexapp.service.NotificationHelper

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)

data class Session(
    val id: String,
    val title: String,
    val workdir: String,
    val lastMessage: String,
    val timestamp: Long,
    val engine: String = "codex"
)

data class WorkspaceDir(
    val path: String,
    val name: String,
    val subdirs: List<WorkspaceDir>
)

class CodexClient(private val context: Context) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val streamText = MutableStateFlow("")
    val currentSessionId = MutableStateFlow("default")

    private val prefs = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
    val serverUrl = MutableStateFlow(run {
        prefs.getString("server_url", null) ?: "http://127.0.0.1:3000"
    })
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionsDir = File(context.filesDir, "sessions")
    private var streamJob: Job? = null
    private val notificationHelper = NotificationHelper(context)

    init {
        sessionsDir.mkdirs()
        loadSessions()
        loadMessages("default")
        checkConnection()
    }

    fun updateServerUrl(url: String) {
        var formattedUrl = url.trim()
        if (formattedUrl.isNotEmpty() && !formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "http://$formattedUrl"
        }
        serverUrl.value = formattedUrl
        prefs.edit().putString("server_url", formattedUrl).apply()
        checkConnection()
    }

    private fun sessionFile(id: String) = File(sessionsDir, "$id.json")

    private fun loadSessions() {
        val list = sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val obj = JSONObject(file.readText())
                    Session(
                        id = obj.getString("id"),
                        title = obj.optString("title", context.getString(R.string.new_session)),
                        workdir = obj.optString("workdir", "/root/workspace"),
                        lastMessage = obj.optString("lastMessage", ""),
                        timestamp = obj.optLong("updatedAt", file.lastModified()),
                        engine = obj.optString("engine", "codex")
                    )
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
        sessions.value = list
    }

    private fun loadMessages(sessionId: String) {
        val file = sessionFile(sessionId)
        if (!file.exists()) {
            messages.value = emptyList()
            return
        }
        try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            val list = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(
                    role = m.getString("role"),
                    content = m.getString("content"),
                    timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    imagePath = if (m.has("imagePath")) m.getString("imagePath") else null
                )
            }
            messages.value = list
        } catch (_: Exception) {
            messages.value = emptyList()
        }
    }

    private fun saveSession(sessionId: String) {
        val file = sessionFile(sessionId)
        if (!file.exists()) return
        try {
            val obj = JSONObject(file.readText())
            val arr = JSONArray()
            messages.value.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("timestamp", m.timestamp)
                    if (m.imagePath != null) {
                        put("imagePath", m.imagePath)
                    }
                })
            }
            obj.put("messages", arr)
            obj.put("updatedAt", System.currentTimeMillis())
            obj.put("lastMessage", messages.value.lastOrNull { it.role == "user" }?.content ?: "")
            file.writeText(obj.toString(2))
        } catch (_: Exception) {}
    }

    fun getSessionsList(): List<Session> {
        return try {
            val list = sessionsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val obj = JSONObject(file.readText())
                        Session(
                            id = obj.getString("id"),
                            title = obj.optString("title", context.getString(R.string.new_session)),
                            workdir = obj.optString("workdir", "/root/workspace"),
                            lastMessage = obj.optString("lastMessage", ""),
                            timestamp = obj.optLong("updatedAt", file.lastModified()),
                            engine = obj.optString("engine", "codex")
                        )
                    } catch (_: Exception) { null }
                }
                ?.sortedByDescending { it.timestamp }
            list ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun autoTitle() = messages.value.firstOrNull { it.role == "user" }?.content?.take(50) ?: context.getString(R.string.new_session)
    private fun getSessionTitle(id: String) = try { JSONObject(sessionFile(id).readText()).optString("title", context.getString(R.string.new_session)) } catch (_: Exception) { context.getString(R.string.new_session) }
    private fun getSessionWorkdir(id: String) = try { JSONObject(sessionFile(id).readText()).optString("workdir", "/root/workspace") } catch (_: Exception) { "/root/workspace" }
    private fun getSessionEngine(id: String) = try { JSONObject(sessionFile(id).readText()).optString("engine", "codex") } catch (_: Exception) { "codex" }

    fun getCurrentSessionId() = currentSessionId.value
    fun getCurrentWorkdir(): String {
        val session = sessions.value.find { it.id == currentSessionId.value }
        return session?.workdir ?: "/root/workspace"
    }

    fun checkConnection() {
        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val code = conn.responseCode
                conn.disconnect()
                isConnected.value = code == 200
            } catch (_: Exception) { isConnected.value = false }
        }
    }

    suspend fun browseWorkspace(dir: String?): WorkspaceDir {
        return withContext(Dispatchers.IO) {
            val url = if (dir != null) "${serverUrl.value}/api/workspaces?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
            else "${serverUrl.value}/api/workspaces"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            val body = if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else "{}"
            conn.disconnect()
            val obj = JSONObject(body)
            val subdirsArr = obj.optJSONArray("subdirs") ?: JSONArray()
            val subdirs = (0 until subdirsArr.length()).map { i ->
                val s = subdirsArr.getJSONObject(i)
                WorkspaceDir(path = s.getString("path"), name = s.getString("name"), subdirs = emptyList())
            }
            WorkspaceDir(path = obj.getString("path"), name = obj.getString("name"), subdirs = subdirs)
        }
    }

    fun sendMessage(content: String, imagePath: String? = null) {
        // Cancel any existing stream
        streamJob?.cancel()

        messages.value = messages.value + ChatMessage(role = "user", content = content, imagePath = imagePath)
        isLoading.value = true
        streamText.value = ""

        streamJob = scope.launch {
            try {
                // Use SSE streaming endpoint
                val conn = URL("${serverUrl.value}/api/chat/stream").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 120000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("message", content)
                    put("sessionId", currentSessionId.value)
                    if (imagePath != null) {
                        val pair = getBase64FromUri(imagePath)
                        if (pair != null) {
                            put("imageBase64", pair.first)
                            put("mimeType", pair.second)
                        }
                    }
                }
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    var line: String?
                    var accumulated = ""

                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (l.startsWith("data: ")) {
                            val data = l.removePrefix("data: ")
                            try {
                                val obj = JSONObject(data)
                                if (obj.has("chunk")) {
                                    accumulated += obj.getString("chunk")
                                    streamText.value = accumulated
                                } else if (obj.has("done")) {
                                    // Finalize
                                    messages.value = messages.value + ChatMessage(
                                        role = "assistant",
                                        content = accumulated.ifBlank { context.getString(R.string.no_output) }
                                    )
                                    streamText.value = ""
                                    saveSession(currentSessionId.value)
                                    loadSessions()

                                    val sessionTitle = getSessionTitle(currentSessionId.value)
                                    notificationHelper.showNotification(
                                        title = context.getString(R.string.notification_response_complete_title),
                                        message = context.getString(R.string.notification_response_complete_desc, sessionTitle),
                                        sessionId = currentSessionId.value
                                    )
                                } else if (obj.has("error")) {
                                    val errorMsg = obj.getString("error")
                                    messages.value = messages.value + ChatMessage(
                                        role = "system",
                                        content = context.getString(R.string.error_prefix, errorMsg)
                                    )
                                    streamText.value = ""

                                    val sessionTitle = getSessionTitle(currentSessionId.value)
                                    notificationHelper.showNotification(
                                        title = context.getString(R.string.notification_response_error_title),
                                        message = context.getString(R.string.notification_response_error_desc, sessionTitle, errorMsg),
                                        sessionId = currentSessionId.value
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    reader.close()
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    messages.value = messages.value + ChatMessage(role = "system", content = context.getString(R.string.server_error_prefix, errBody))

                    val sessionTitle = getSessionTitle(currentSessionId.value)
                    notificationHelper.showNotification(
                        title = context.getString(R.string.notification_server_error_title),
                        message = context.getString(R.string.notification_response_error_desc, sessionTitle, errBody),
                        sessionId = currentSessionId.value
                    )
                }
                conn.disconnect()
            } catch (e: CancellationException) {
                // Stream was cancelled, that's OK
            } catch (e: Exception) {
                val errorMsg = e.message ?: context.getString(R.string.connection_failed)
                messages.value = messages.value + ChatMessage(
                    role = "system",
                    content = context.getString(R.string.conn_error_desc, errorMsg)
                )

                val sessionTitle = getSessionTitle(currentSessionId.value)
                notificationHelper.showNotification(
                    title = context.getString(R.string.notification_conn_error_title),
                    message = context.getString(R.string.notification_response_error_desc, sessionTitle, errorMsg),
                    sessionId = currentSessionId.value
                )
            } finally {
                isLoading.value = false
            }
        }
    }

    fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
        isLoading.value = false
        if (streamText.value.isNotBlank()) {
            messages.value = messages.value + ChatMessage(
                role = "assistant",
                content = streamText.value
            )
            streamText.value = ""
            saveSession(currentSessionId.value)
            loadSessions()
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == currentSessionId.value) return
        streamJob?.cancel()
        currentSessionId.value = sessionId
        loadMessages(sessionId)
        streamText.value = ""
    }

    fun newSession(title: String? = null, workdir: String = "/root/workspace") {
        val sessionTitle = if (title.isNullOrBlank()) context.getString(R.string.new_session) else title
        scope.launch {
            try {
                val json = JSONObject().apply { put("title", sessionTitle); put("workdir", workdir) }
                val conn = URL("${serverUrl.value}/api/sessions").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val body = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else ""
                conn.disconnect()
                if (body.isNotBlank()) {
                    val obj = JSONObject(body)
                    val id = obj.getString("id")
                    sessionFile(id).writeText(JSONObject().apply {
                        put("id", id); put("title", sessionTitle); put("workdir", workdir); put("engine", "codex")
                        put("messages", JSONArray()); put("updatedAt", System.currentTimeMillis())
                        put("lastMessage", "")
                    }.toString(2))
                    currentSessionId.value = id
                    messages.value = emptyList()
                    loadSessions()
                }
            } catch (_: Exception) {
                val id = UUID.randomUUID().toString()
                messages.value = emptyList()
                currentSessionId.value = id
                sessionFile(id).writeText(JSONObject().apply {
                    put("id", id); put("title", sessionTitle); put("workdir", workdir); put("engine", "codex")
                    put("messages", JSONArray()); put("updatedAt", System.currentTimeMillis())
                    put("lastMessage", "")
                }.toString(2))
                loadSessions()
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/sessions/$sessionId").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "DELETE"
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
            sessionFile(sessionId).delete()
            if (sessionId == currentSessionId.value) { currentSessionId.value = "default"; loadMessages("default") }
            loadSessions()
        }
    }

    fun changeSessionEngine(sessionId: String, engine: String) {
        scope.launch {
            try {
                val file = sessionFile(sessionId)
                if (file.exists()) {
                    val obj = JSONObject(file.readText())
                    obj.put("engine", engine)
                    file.writeText(obj.toString(2))
                    loadSessions()
                }
                val json = JSONObject().apply { put("engine", engine) }
                val conn = URL("${serverUrl.value}/api/sessions/$sessionId/engine").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun copyImageToLocalStorage(uri: android.net.Uri): String? {
        return try {
            val imagesDir = File(context.filesDir, "chat_images")
            if (!imagesDir.exists()) {
                imagesDir.mkdirs()
            }
            val extension = context.contentResolver.getType(uri)?.split("/")?.lastOrNull() ?: "png"
            val destFile = File(imagesDir, "img_${UUID.randomUUID()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getBase64FromUri(path: String): Pair<String, String>? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/png"
            }
            Pair(base64, mimeType)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
