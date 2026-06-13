package com.codexapp.network

import org.json.JSONObject
import org.json.JSONArray

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val tags: List<String>,
    val content: String,
    val isEnabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("version", version)
            put("author", author)
            put("tags", JSONArray(tags))
            put("content", content)
            put("isEnabled", isEnabled)
            put("installedAt", installedAt)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Skill {
            val tagsArr = json.optJSONArray("tags")
            val tagList = if (tagsArr != null) {
                (0 until tagsArr.length()).map { i -> tagsArr.getString(i) }
            } else emptyList()
            return Skill(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.getString("description"),
                version = json.optString("version", "1.0.0"),
                author = json.optString("author", ""),
                tags = tagList,
                content = json.optString("content", ""),
                isEnabled = json.optBoolean("isEnabled", true),
                installedAt = json.optLong("installedAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        fun parseMetadataFromMd(content: String): SkillMetadata {
            val lines = content.lines()
            var inFrontmatter = false
            var name = "Unnamed Skill"
            var description = ""
            var version = "1.0.0"
            var author = ""
            var tags = emptyList<String>()
            
            var frontmatterLineCount = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == "---") {
                    inFrontmatter = !inFrontmatter
                    frontmatterLineCount++
                    if (frontmatterLineCount == 2) break
                    continue
                }
                if (inFrontmatter) {
                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        when (key) {
                            "name" -> name = value
                            "description" -> description = value
                            "version" -> version = value
                            "author" -> author = value
                            "tags" -> {
                                val cleanVal = value.removeSurrounding("[", "]")
                                tags = cleanVal.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            }
                        }
                    }
                }
            }
            return SkillMetadata(name, description, version, author, tags)
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val tags: List<String>
)

data class PermissionRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: PermissionType,
    val title: String,
    val description: String,
    val details: JSONObject = JSONObject(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: PermissionStatus = PermissionStatus.PENDING
) {
    enum class PermissionType {
        FILE_READ, FILE_WRITE, SHELL_COMMAND, NETWORK_REQUEST, 
        GIT_OPERATION, PACKAGE_INSTALL, SYSTEM_ACCESS
    }

    enum class PermissionStatus {
        PENDING, GRANTED, DENIED, GRANTED_ALWAYS, DENIED_ALWAYS
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("title", title)
            put("description", description)
            put("details", details)
            put("timestamp", timestamp)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PermissionRequest {
            return PermissionRequest(
                id = json.getString("id"),
                type = PermissionType.valueOf(json.getString("type")),
                title = json.getString("title"),
                description = json.getString("description"),
                details = json.optJSONObject("details") ?: JSONObject(),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                status = PermissionStatus.valueOf(json.optString("status", "PENDING"))
            )
        }
    }
}

data class AgentConfig(
    val engine: String = "codex",
    val model: String = "codex-ollama",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val systemPrompt: String = "",
    val enabledTools: List<String> = listOf("read", "write", "bash", "grep", "glob", "task"),
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val autoApprove: Boolean = false
) {
    enum class ApprovalMode {
        ASK, AUTO_APPROVE, AUTO_DENY
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("engine", engine)
            put("model", model)
            put("temperature", temperature)
            put("maxTokens", maxTokens)
            put("systemPrompt", systemPrompt)
            put("enabledTools", JSONArray(enabledTools))
            put("approvalMode", approvalMode.name)
            put("autoApprove", autoApprove)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AgentConfig {
            val toolsArr = json.optJSONArray("enabledTools")
            val tools = if (toolsArr != null) {
                (0 until toolsArr.length()).map { i -> toolsArr.getString(i) }
            } else listOf("read", "write", "bash", "grep", "glob", "task")
            return AgentConfig(
                engine = json.optString("engine", "codex"),
                model = json.optString("model", "codex-ollama"),
                temperature = json.optDouble("temperature", 0.7).toFloat(),
                maxTokens = json.optInt("maxTokens", 4096),
                systemPrompt = json.optString("systemPrompt", ""),
                enabledTools = tools,
                approvalMode = ApprovalMode.valueOf(json.optString("approvalMode", "ASK")),
                autoApprove = json.optBoolean("autoApprove", false)
            )
        }
    }
}
