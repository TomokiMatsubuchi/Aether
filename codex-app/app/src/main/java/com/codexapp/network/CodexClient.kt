package com.codexapp.network

import android.content.Context
import android.content.SharedPreferences
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

data class GitFile(
    val path: String,
    val status: String
)

data class GitStatus(
    val isGit: Boolean,
    val files: List<GitFile>
)

class CodexClient(private val context: Context) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val streamText = MutableStateFlow("")
    val currentSessionId = MutableStateFlow("default")
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val pendingPermissions = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val agentConfig = MutableStateFlow(AgentConfig())

    private val prefs = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
    val serverUrl = MutableStateFlow(run {
        prefs.getString("server_url", null) ?: "http://127.0.0.1:3000"
    })

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "server_url") {
            val newUrl = sharedPreferences.getString("server_url", null) ?: "http://127.0.0.1:3000"
            if (serverUrl.value != newUrl) {
                serverUrl.value = newUrl
                checkConnection()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionsDir = File(context.filesDir, "sessions")
    private val skillsDir = File(context.filesDir, "skills")
    private var streamJob: Job? = null
    private val notificationHelper = NotificationHelper(context)

    init {
        sessionsDir.mkdirs()
        skillsDir.mkdirs()
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        loadSessions()
        loadMessages("default")
        loadSkills()
        loadAgentConfig()
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
    private fun skillFile(id: String) = File(skillsDir, "$id.json")

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
            obj.put("lastMessage", messages.value.lastOrNull()?.content ?: "")
            obj.put("updatedAt", System.currentTimeMillis())
            file.writeText(obj.toString(2))
            loadSessions()
        } catch (_: Exception) {}
    }

    fun switchSession(sessionId: String) {
        currentSessionId.value = sessionId
        loadMessages(sessionId)
    }

    fun newSession(sessionTitle: String = "", workdir: String = "/root/workspace") {
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

    suspend fun browseWorkspace(dir: String?): WorkspaceDir {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = "${serverUrl.value}/api/workspaces"
                val url = if (dir != null) "$baseUrl?dir=$dir" else baseUrl
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val input = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                parseWorkspaceDir(JSONObject(input))
            } catch (e: Exception) {
                android.util.Log.e("CodexClient", "browseWorkspace failed: ${e.message}", e)
                WorkspaceDir("/root/workspace", "workspace", emptyList())
            }
        }
    }

    private fun parseWorkspaceDir(obj: JSONObject): WorkspaceDir {
        val subdirs = obj.optJSONArray("subdirs")?.let { arr ->
            (0 until arr.length()).map { parseWorkspaceDir(arr.getJSONObject(it)) }
        } ?: emptyList()
        return WorkspaceDir(
            path = obj.getString("path"),
            name = obj.getString("name"),
            subdirs = subdirs
        )
    }

    fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
    }

    fun sendMessage(text: String, imagePath: String? = null) {
        if (text.isBlank() && imagePath == null) return
        isLoading.value = true
        streamText.value = ""
        streamJob?.cancel()
        streamJob = scope.launch {
            val sessionId = currentSessionId.value
            val workdir = sessions.value.find { it.id == sessionId }?.workdir ?: "/root/workspace"
            val engine = sessions.value.find { it.id == sessionId }?.engine ?: "codex"

            val userMessage = ChatMessage(role = "user", content = text, imagePath = imagePath)
            messages.value = messages.value + userMessage
            saveSession(sessionId)

            try {
                val url = URL("${serverUrl.value}/api/chat/stream")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 0
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.doOutput = true

                val requestBody = JSONObject().apply {
                    put("message", text)
                    put("workdir", workdir)
                    put("engine", engine)
                    if (imagePath != null) put("imagePath", imagePath)
                }.toString()

                OutputStreamWriter(conn.outputStream).use { it.write(requestBody) }

                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: conn.responseMessage
                    throw Exception("Server error: $err")
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var fullResponse = ""

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            when {
                                json.has("chunk") -> {
                                    val chunk = json.getString("chunk")
                                    fullResponse += chunk
                                    streamText.value = fullResponse
                                }
                                json.has("done") -> break
                                json.has("error") -> throw Exception(json.getString("error"))
                                json.has("permission_request") -> {
                                    handlePermissionRequest(json.getJSONObject("permission_request"))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore parse errors for non-fatal lines
                        }
                    }
                }

                reader.close()
                conn.disconnect()

                val assistantMessage = ChatMessage(role = "assistant", content = fullResponse.ifBlank { "(no output)" })
                messages.value = messages.value + assistantMessage
                saveSession(sessionId)

                val sessionTitle = sessions.value.find { it.id == sessionId }?.title ?: "Session"
                notificationHelper.showResponseComplete(sessionTitle)

            } catch (e: Exception) {
                val errorMsg = context.getString(R.string.error_prefix, e.message ?: "Unknown error")
                val errorMessage = ChatMessage(role = "system", content = errorMsg)
                messages.value = messages.value + errorMessage
                saveSession(sessionId)

                val sessionTitle = sessions.value.find { it.id == sessionId }?.title ?: "Session"
                notificationHelper.showResponseError(sessionTitle, e.message ?: "Unknown error")
            } finally {
                isLoading.value = false
                streamText.value = ""
            }
        }
    }

    private fun handlePermissionRequest(json: JSONObject) {
        try {
            val request = PermissionRequest.fromJson(json)
            val alwaysDecision = checkAlwaysPermission(request.type)
            if (alwaysDecision != null) {
                // Auto-respond to server using saved "always" decision
                respondToPermission(request.id, alwaysDecision, remember = true)
                return
            }
            val current = pendingPermissions.value
            pendingPermissions.value = current + request
        } catch (_: Exception) {}
    }

    fun applyAutoResolvedPermission(json: JSONObject) {
        // For "always" decisions, just keep client state in sync
        try {
            val request = PermissionRequest.fromJson(json)
            val current = pendingPermissions.value
            pendingPermissions.value = current + request
        } catch (_: Exception) {}
    }

    fun loadAlwaysPermissions() {
        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/permissions/always").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (text.isNotBlank()) {
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("always")
                    if (arr != null) {
                        val saved = mutableMapOf<String, Boolean>()
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            saved[item.getString("type")] = item.getBoolean("granted")
                        }
                        prefs.edit().putString("always_permissions", JSONObject(saved as Map<String, *>).toString()).apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun checkAlwaysPermission(type: PermissionRequest.PermissionType): Boolean? {
        // Returns: true if always granted, false if always denied, null if no rule
        val json = prefs.getString("always_permissions", "") ?: ""
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            if (obj.has(type.name)) obj.getBoolean(type.name) else null
        } catch (_: Exception) { null }
    }

    fun setAlwaysPermission(type: PermissionRequest.PermissionType, granted: Boolean) {
        val json = prefs.getString("always_permissions", "") ?: ""
        val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
        obj.put(type.name, granted)
        prefs.edit().putString("always_permissions", obj.toString()).apply()
    }

    fun respondToPermission(requestId: String, granted: Boolean, remember: Boolean) {
        val current = pendingPermissions.value
        val targetType = current.find { it.id == requestId }?.type
        val updated = current.mapNotNull { req ->
            if (req.id == requestId) {
                if (remember) {
                    // Persist always-grant/deny decision
                    val finalStatus = if (granted)
                        PermissionRequest.PermissionStatus.GRANTED_ALWAYS
                    else
                        PermissionRequest.PermissionStatus.DENIED_ALWAYS
                    PermissionRequest(
                        id = req.id,
                        type = req.type,
                        title = req.title,
                        description = req.description,
                        details = req.details,
                        timestamp = req.timestamp,
                        status = finalStatus
                    )
                } else {
                    // Non-remembered decisions are removed from the queue
                    null
                }
            } else {
                req
            }
        }
        pendingPermissions.value = updated

        if (remember && targetType != null) {
            setAlwaysPermission(targetType, granted)
        }

        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/permissions/$requestId/respond").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val response = JSONObject().apply {
                    put("granted", granted)
                    put("remember", remember)
                }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(response) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun checkConnection() {
        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                isConnected.value = conn.responseCode == 200
                conn.disconnect()
            } catch (_: Exception) {
                isConnected.value = false
            }
        }
    }

    fun getGitStatus(workdir: String): GitStatus {
        return try {
            val conn = URL("${serverUrl.value}/api/git/status?dir=$workdir").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val input = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(input)
            val files = obj.optJSONArray("files")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val f = arr.getJSONObject(i)
                    GitFile(f.getString("path"), f.getString("status"))
                }
            } ?: emptyList()
            GitStatus(obj.getBoolean("isGit"), files)
        } catch (_: Exception) {
            GitStatus(false, emptyList())
        }
    }

    fun getGitDiff(workdir: String, filePath: String?): String {
        return try {
            val url = "${serverUrl.value}/api/git/diff?dir=$workdir${if (filePath != null) "&file=$filePath" else ""}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val input = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(input).getString("diff")
        } catch (_: Exception) {
            ""
        }
    }

    fun gitInit(workdir: String): Boolean {
        return try {
            val conn = URL("${serverUrl.value}/api/git/init").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().put("dir", workdir).toString()) }
            val input = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(input).getBoolean("success")
        } catch (_: Exception) {
            false
        }
    }

    private fun loadSkills() {
        val list = skillsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    Skill.fromJson(JSONObject(file.readText()))
                } catch (_: Exception) { null }
            }
            ?.sortedBy { it.name }
            ?: emptyList()
        skills.value = list
    }

    suspend fun getMarketplaceSkills(): List<Skill> {
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("${serverUrl.value}/api/marketplace/skills").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (text.isNotBlank()) {
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("skills") ?: JSONArray()
                    (0 until arr.length()).map { i ->
                        Skill.fromJson(arr.getJSONObject(i))
                    }
                } else emptyList()
            } catch (e: Exception) {
                android.util.Log.e("CodexClient", "getMarketplaceSkills failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    fun importSkillFromUrl(urlStr: String, onSuccess: (Skill) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val content = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                if (content.isBlank()) {
                    withContext(Dispatchers.Main) { onError("Downloaded content is empty") }
                    return@launch
                }
                
                val metadata = Skill.parseMetadataFromMd(content)
                val skill = Skill(
                    id = UUID.randomUUID().toString(),
                    name = metadata.name,
                    description = metadata.description,
                    version = metadata.version,
                    author = metadata.author,
                    tags = metadata.tags,
                    content = content,
                    isEnabled = true
                )
                withContext(Dispatchers.Main) {
                    onSuccess(skill)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Failed to download skill")
                }
            }
        }
    }

    fun installSkill(skill: Skill) {
        skillFile(skill.id).writeText(skill.toJson().toString(2))
        loadSkills()
        scope.launch { syncSkillToServer(skill) }
    }

    fun uninstallSkill(skillId: String) {
        skillFile(skillId).delete()
        loadSkills()
        scope.launch { deleteSkillFromServer(skillId) }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        val file = skillFile(skillId)
        if (file.exists()) {
            try {
                val skill = Skill.fromJson(JSONObject(file.readText())).copy(isEnabled = enabled)
                file.writeText(skill.toJson().toString(2))
                loadSkills()
                scope.launch { syncSkillToServer(skill) }
            } catch (_: Exception) {}
        }
    }

    private fun syncSkillToServer(skill: Skill) {
        try {
            val conn = URL("${serverUrl.value}/api/skills").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(skill.toJson().toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun deleteSkillFromServer(skillId: String) {
        try {
            val conn = URL("${serverUrl.value}/api/skills/$skillId").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.requestMethod = "DELETE"
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun loadAgentConfig() {
        val jsonStr = prefs.getString("agent_config", "") ?: ""
        if (jsonStr.isNotBlank()) {
            try {
                agentConfig.value = AgentConfig.fromJson(JSONObject(jsonStr))
            } catch (_: Exception) {}
        }
        scope.launch { syncAgentConfigFromServer() }
    }

    private fun syncAgentConfigFromServer() {
        try {
            val conn = URL("${serverUrl.value}/api/agent/config").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (text.isNotBlank()) {
                val config = AgentConfig.fromJson(JSONObject(text))
                agentConfig.value = config
                prefs.edit().putString("agent_config", config.toJson().toString()).apply()
            }
        } catch (_: Exception) {}
    }

    fun updateAgentConfig(config: AgentConfig) {
        agentConfig.value = config
        prefs.edit().putString("agent_config", config.toJson().toString()).apply()
        scope.launch {
            try {
                val conn = URL("${serverUrl.value}/api/agent/config").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(config.toJson().toString()) }
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

    fun getCurrentWorkdir(): String {
        val currentId = currentSessionId.value
        return sessions.value.find { it.id == currentId }?.workdir ?: "/root/workspace"
    }
}
