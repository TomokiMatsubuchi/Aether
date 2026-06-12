package com.codexapp.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class CodexHttpServer(private val context: Context) {
    companion object {
        const val TAG = "CodexHttpServer"
        const val PORT = 3001
    }

    val isRunning = MutableStateFlow(false)
    private var serverSocket: ServerSocket? = null
    private val sessionsDir = File(context.filesDir, "sessions")
    private val sessions = mutableMapOf<String, JSONObject>()
    private val executor = Executors.newFixedThreadPool(8)

    init {
        sessionsDir.mkdirs()
        loadSessions()
    }

    fun start() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(PORT), 50)
            }
            isRunning.value = true
            Log.i(TAG, "Server started on port $PORT")

            executor.execute {
                while (isRunning.value) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning.value) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            isRunning.value = false
        }
    }

    fun stop() {
        isRunning.value = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    // === Session management ===

    private fun loadSessions() {
        synchronized(sessions) {
            sessionsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                try {
                    val obj = JSONObject(file.readText())
                    sessions[obj.getString("id")] = obj
                } catch (_: Exception) {}
            }
            if (!sessions.containsKey("default")) {
                initDefault()
            }
        }
    }

    private fun initDefault() {
        val home = getHome()
        val session = JSONObject().apply {
            put("id", "default")
            put("title", "New Session")
            put("workdir", home)
            put("messages", JSONArray())
            put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
            put("lastMessage", "")
        }
        sessions["default"] = session
        saveSessionFile("default")
    }

    private fun saveSessionFile(id: String) {
        synchronized(sessions) {
            sessions[id]?.let {
                File(sessionsDir, "$id.json").writeText(it.toString(2))
            }
        }
    }

    private fun getHome(): String {
        val paths = listOf(
            "/root/workspace",
            "/storage/emulated/0",
            context.filesDir.absolutePath
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.isDirectory && file.canRead()) {
                return path
            }
        }
        return context.filesDir.absolutePath
    }

    // === HTTP handling ===

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()

                // Read request line
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]
                val path = fullPath.substringBefore("?")

                // Read headers
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // Read body using readFully to ensure all bytes are read
                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = input.read(chars, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(chars, 0, totalRead)
                } else ""

                // CORS preflight
                if (method == "OPTIONS") {
                    sendResponse(output, 204, "No Content", "", corsHeaders())
                    return
                }
                
                Log.d(TAG, "$method $path bodyLen=${body.length}")

                try {
                    when {
                        path == "/api/health" && method == "GET" -> handleHealth(output)
                        path == "/api/sessions" && method == "GET" -> handleGetSessions(output)
                        path == "/api/sessions" && method == "POST" -> handleCreateSession(output, body)
                        path.startsWith("/api/sessions/") && method == "DELETE" -> handleDeleteSession(output, path)
                        path == "/api/workspaces" && method == "GET" -> handleWorkspaces(output, fullPath.substringAfter("?"))
                        path == "/api/chat" && method == "POST" -> handleChat(output, body)
                        path == "/api/chat/stream" && method == "POST" -> handleChatStream(output, body)
                        path == "/api/git/status" && method == "GET" -> handleGitStatus(output, fullPath.substringAfter("?"))
                        path == "/api/git/init" && method == "POST" -> handleGitInit(output, body)
                        path == "/api/git/diff" && method == "GET" -> handleGitDiff(output, fullPath.substringAfter("?"))
                        else -> sendResponse(output, 404, "Not Found", """{"error":"Not found"}""", corsHeaders())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling $method $path: ${e.message}", e)
                    try {
                        sendResponse(output, 500, "Internal Error", """{"error":"${e.message?.replace("\"", "\\\"")}"}""", corsHeaders())
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}")
        }
    }

    private fun corsHeaders() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization"
    )

    private fun sendResponse(output: OutputStream, code: Int, status: String, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Content-Length" to bytes.size.toString()
        )
        headers.putAll(extraHeaders)

        val response = buildString {
            append("HTTP/1.1 $code $status\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        output.write(response.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun sendSSEHeader(output: OutputStream) {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray())
        output.flush()
    }

    private fun writeSSE(output: OutputStream, event: String, data: String) {
        val sse = buildString {
            if (event.isNotEmpty()) append("event: $event\n")
            append("data: $data\n\n")
        }
        try {
            output.write(sse.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }

    // === Handlers ===

    private fun handleHealth(output: OutputStream) {
        val json = JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())
        }
        sendResponse(output, 200, "OK", json.toString())
    }

    private fun handleGetSessions(output: OutputStream) {
        val list = synchronized(sessions) {
            sessions.values.map { s ->
                JSONObject().apply {
                    put("id", s.getString("id"))
                    put("title", s.optString("title", "New Session"))
                    put("workdir", s.optString("workdir", getHome()))
                    put("lastMessage", s.optString("lastMessage", ""))
                    put("timestamp", s.optLong("updatedAt", s.optLong("createdAt", System.currentTimeMillis())))
                }
            }.sortedByDescending { it.optLong("timestamp") }
        }
        sendResponse(output, 200, "OK", JSONArray(list).toString())
    }

    private fun handleCreateSession(output: OutputStream, body: String) {
        Log.d(TAG, "createSession body: $body")
        val input = JSONObject(body)
        val id = UUID.randomUUID().toString()
        val session = JSONObject().apply {
            put("id", id)
            put("title", input.optString("title", "New Session"))
            put("workdir", input.optString("workdir", getHome()))
            put("messages", JSONArray())
            put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
            put("lastMessage", "")
        }
        synchronized(sessions) {
            sessions[id] = session
            saveSessionFile(id)
        }
        sendResponse(output, 201, "Created", session.toString())
    }

    private fun handleDeleteSession(output: OutputStream, path: String) {
        val id = path.substringAfterLast("/")
        if (id.isBlank() || id == "sessions") {
            sendResponse(output, 400, "Bad Request", """{"error":"Session ID required"}""")
            return
        }
        synchronized(sessions) {
            if (sessions.containsKey(id)) {
                sessions.remove(id)
                File(sessionsDir, "$id.json").delete()
            }
        }
        sendResponse(output, 200, "OK", """{"deleted":true}""")
    }

    // === Workspaces ===


    private fun handleWorkspaces(output: OutputStream, query: String) {
        // Parse ?dir=... from query string
        val dir = if (query.startsWith("dir=")) {
            java.net.URLDecoder.decode(query.substring(4), "UTF-8")
        } else {
            getHome()
        }
        val targetDir = File(dir)
        val result = JSONObject()
        result.put("path", targetDir.absolutePath)
        result.put("name", targetDir.name.ifEmpty { "/" })
        val subdirs = JSONArray()
        if (targetDir.exists() && targetDir.isDirectory) {
            targetDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" && it.name != "lost+found" }?.forEach { subdir ->
                val item = JSONObject()
                item.put("path", subdir.absolutePath)
                item.put("name", subdir.name)
                item.put("isProject", true)
                subdirs.put(item)
            }
        }
        result.put("subdirs", subdirs)
        sendResponse(output, 200, "OK", result.toString())
    }


    // === Chat (sync) ===

    private fun handleChat(output: OutputStream, body: String) {
        val input = JSONObject(body)
        val message = input.optString("message", "")
        val sessionId = input.optString("sessionId", "default")

        if (message.isBlank()) {
            sendResponse(output, 400, "Bad Request", """{"error":"Message required"}""")
            return
        }

        val session = synchronized(sessions) {
            sessions.getOrPut(sessionId) { createFreshSession(sessionId) }
        }
        appendUserMessage(session, message)

        try {
            val response = callLLMApi(session)
            session.put("updatedAt", System.currentTimeMillis())
            saveSessionFile(sessionId)
            val result = JSONObject().apply {
                put("response", response)
                put("sessionId", sessionId)
            }
            sendResponse(output, 200, "OK", result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "LLM error: ${e.message}")
            appendMessage(session, "system", "Error: ${e.message}")
            saveSessionFile(sessionId)
            sendResponse(output, 500, "Error", """{"error":"${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    // === Chat Stream (SSE) ===

    private fun handleChatStream(output: OutputStream, body: String) {
        val input = JSONObject(body)
        val message = input.optString("message", "")
        val sessionId = input.optString("sessionId", "default")

        if (message.isBlank()) {
            sendResponse(output, 400, "Bad Request", """{"error":"Message required"}""")
            return
        }

        val session = synchronized(sessions) {
            sessions.getOrPut(sessionId) { createFreshSession(sessionId) }
        }
        appendUserMessage(session, message)

        sendSSEHeader(output)

        val fullResponse = StringBuilder()

        try {
            val workdir = session.optString("workdir", getHome())
            val cwd = if (workdir.isNotEmpty() && File(workdir).exists()) File(workdir) else File(getHome())

            val pb = ProcessBuilder(
                "codex",
                "-c", "model=deepseek-v4-pro:cloud",
                "-c", "model_provider=ollama_cloud",
                "-c", "model_context_window=128000",
                "-c", "model_providers.ollama_cloud.name=Ollama Cloud",
                "-c", "model_providers.ollama_cloud.base_url=https://ollama.com/v1",
                "-c", "model_providers.ollama_cloud.env_key=OLLAMA_API_KEY",
                "-c", "model_providers.ollama_cloud.wire_api=responses",
                "-c", "model_providers.ollama_cloud.supports_websockets=false",
                "exec",
                "--skip-git-repo-check",
                message
            )
            pb.directory(cwd)
            val env = pb.environment()
            env["HOME"] = "/root"
            System.getenv("OLLAMA_API_KEY")?.let { env["OLLAMA_API_KEY"] = it }
            System.getenv("PATH")?.let { env["PATH"] = it }

            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                val chunk = String(buffer, 0, read)
                if (chunk.isNotEmpty()) {
                    fullResponse.append(chunk)
                    writeSSE(output, "chunk", JSONObject().put("chunk", chunk).toString())
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorOutput = errorReader.readText()
                if (fullResponse.isEmpty()) {
                    writeSSE(output, "error", JSONObject().put("error", "Codex exited with $exitCode: $errorOutput").toString())
                    return
                }
            }

            val finalContent = fullResponse.toString().trim().ifBlank { "(no output)" }
            appendMessage(session, "assistant", finalContent)
            session.put("updatedAt", System.currentTimeMillis())
            saveSessionFile(sessionId)

            writeSSE(output, "done", JSONObject().apply {
                put("done", true)
                put("sessionId", sessionId)
            }.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            try {
                writeSSE(output, "error", JSONObject().put("error", e.message ?: "Stream error").toString())
            } catch (_: Exception) {}
        }
    }

    // === Helpers ===

    private fun createFreshSession(id: String): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", "New Session")
            put("workdir", getHome())
            put("messages", JSONArray())
            put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
            put("lastMessage", "")
        }
    }

    private fun appendUserMessage(session: JSONObject, message: String) {
        appendMessage(session, "user", message)
        session.put("lastMessage", message)
        if (session.optString("title") == "New Session") {
            session.put("title", message.take(50))
        }
    }

    private fun appendMessage(session: JSONObject, role: String, content: String) {
        synchronized(sessions) {
            val messages = session.optJSONArray("messages") ?: JSONArray()
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            })
            session.put("messages", messages)
            session.put("updatedAt", System.currentTimeMillis())
        }
    }

    private fun buildMessagesArray(session: JSONObject): JSONArray {
        val messages = session.optJSONArray("messages") ?: JSONArray()
        val apiMessages = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") in listOf("user", "assistant")) {
                apiMessages.put(JSONObject().apply {
                    put("role", m.getString("role"))
                    put("content", m.getString("content"))
                })
            }
        }
        return apiMessages
    }

    private fun callLLMApi(session: JSONObject): String {
        val messages = session.optJSONArray("messages") ?: JSONArray()
        val lastUserMessage = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.getString("role") == "user" }
            .lastOrNull()?.getString("content") ?: throw Exception("No user message")

        val workdir = session.optString("workdir", getHome())
        val cwd = if (workdir.isNotEmpty() && File(workdir).exists()) File(workdir) else File(getHome())

        val pb = ProcessBuilder(
            "codex",
            "-c", "model=deepseek-v4-pro:cloud",
            "-c", "model_provider=ollama_cloud",
            "-c", "model_context_window=128000",
            "-c", "model_providers.ollama_cloud.name=Ollama Cloud",
            "-c", "model_providers.ollama_cloud.base_url=https://ollama.com/v1",
            "-c", "model_providers.ollama_cloud.env_key=OLLAMA_API_KEY",
            "-c", "model_providers.ollama_cloud.wire_api=responses",
            "-c", "model_providers.ollama_cloud.supports_websockets=false",
            "exec",
            "--skip-git-repo-check",
            lastUserMessage
        )
        pb.directory(cwd)
        val env = pb.environment()
        env["HOME"] = "/root"
        System.getenv("OLLAMA_API_KEY")?.let { env["OLLAMA_API_KEY"] = it }
        System.getenv("PATH")?.let { env["PATH"] = it }

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))

        val output = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorOutput = errorReader.readText()
            throw Exception("Codex exited with $exitCode: $errorOutput")
        }

        val response = output.toString().trim()
        appendMessage(session, "assistant", response)
        return response.ifBlank { "(no output)" }
    }

    private fun runCmd(args: List<String>, cwd: File): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(args)
            pb.directory(cwd)
            val env = pb.environment()
            env["HOME"] = "/root"
            System.getenv("PATH")?.let { env["PATH"] = it }
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode, output.trim())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Execution failed")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (query.isBlank()) return result
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx != -1) {
                val key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun handleGitStatus(output: OutputStream, query: String) {
        val params = parseQuery(query)
        val dir = params["dir"] ?: getHome()
        val workdir = File(dir)
        if (!workdir.exists() || !workdir.isDirectory) {
            sendResponse(output, 404, "Not Found", """{"error":"Directory not found"}""", corsHeaders())
            return
        }

        val (checkCode, _) = runCmd(listOf("git", "rev-parse", "--is-inside-work-tree"), workdir)
        if (checkCode != 0) {
            sendResponse(output, 200, "OK", """{"isGit":false,"files":[]}""", corsHeaders())
            return
        }

        val (statusCode, statusOutput) = runCmd(listOf("git", "status", "--porcelain"), workdir)
        val filesArr = JSONArray()
        if (statusCode == 0 && statusOutput.isNotEmpty()) {
            statusOutput.split("\n").forEach { line ->
                if (line.length >= 4) {
                    val status = line.substring(0, 2).trim()
                    var filePath = line.substring(3).trim()
                    if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
                        filePath = filePath.substring(1, filePath.length - 1)
                    }
                    filesArr.put(JSONObject().apply {
                        put("path", filePath)
                        put("status", status)
                    })
                }
            }
        }
        val result = JSONObject().apply {
            put("isGit", true)
            put("files", filesArr)
        }
        sendResponse(output, 200, "OK", result.toString(), corsHeaders())
    }

    private fun handleGitInit(output: OutputStream, body: String) {
        val input = JSONObject(body)
        val dir = input.optString("dir", getHome())
        val workdir = File(dir)
        if (!workdir.exists() || !workdir.isDirectory) {
            sendResponse(output, 404, "Not Found", """{"error":"Directory not found"}""", corsHeaders())
            return
        }

        val (initCode, initOut) = runCmd(listOf("git", "init"), workdir)
        if (initCode != 0) {
            sendResponse(output, 500, "Error", """{"error":"git init failed: $initOut"}""", corsHeaders())
            return
        }

        runCmd(listOf("git", "add", "."), workdir)
        runCmd(listOf("git", "commit", "-m", "Initial commit by Aether"), workdir)

        sendResponse(output, 200, "OK", """{"success":true}""", corsHeaders())
    }

    private fun handleGitDiff(output: OutputStream, query: String) {
        val params = parseQuery(query)
        val dir = params["dir"] ?: getHome()
        val filePath = params["file"]
        val workdir = File(dir)
        if (!workdir.exists() || !workdir.isDirectory) {
            sendResponse(output, 404, "Not Found", """{"error":"Directory not found"}""", corsHeaders())
            return
        }

        if (filePath.isNullOrEmpty()) {
            val (_, diffOut) = runCmd(listOf("git", "diff", "--no-color"), workdir)
            sendResponse(output, 200, "OK", JSONObject().put("diff", diffOut).toString(), corsHeaders())
            return
        }

        val (_, statusOut) = runCmd(listOf("git", "status", "--porcelain", filePath), workdir)
        val isUntracked = statusOut.startsWith("??")

        val diffArgs = if (isUntracked) {
            listOf("git", "diff", "--no-color", "--no-index", "/dev/null", filePath)
        } else {
            listOf("git", "diff", "--no-color", filePath)
        }

        val (_, diffOut) = runCmd(diffArgs, workdir)
        sendResponse(output, 200, "OK", JSONObject().put("diff", diffOut).toString(), corsHeaders())
    }
}
