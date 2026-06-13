package com.codexapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.R
import com.codexapp.network.CodexClient
import com.codexapp.network.Skill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    codexClient: CodexClient,
    skill: Skill? = null,
    onBack: () -> Unit
) {
    val defaultTemplate = """
        ---
        name: my-first-skill
        description: "A description of the skill"
        version: 1.0.0
        author: Your Name
        tags: [tag1, tag2]
        ---

        # My First Skill

        Write details or prompt instructions for the agent here.
    """.trimIndent()

    var content by remember { mutableStateOf(skill?.content ?: defaultTemplate) }
    val isEdit = skill != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEdit) stringResource(R.string.edit_skill) else stringResource(R.string.new_skill),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val metadata = Skill.parseMetadataFromMd(content)
                            val finalSkill = Skill(
                                id = skill?.id ?: java.util.UUID.randomUUID().toString(),
                                name = metadata.name,
                                description = metadata.description,
                                version = metadata.version,
                                author = metadata.author,
                                tags = metadata.tags,
                                content = content,
                                isEnabled = skill?.isEnabled ?: true
                            )
                            codexClient.installSkill(finalSkill)
                            onBack()
                        }
                    ) {
                        Icon(Icons.Default.Check, stringResource(R.string.save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "YAMLフロントマターを含む Markdown (SKILL.md) の内容を記述してください。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("Markdown content with YAML frontmatter...") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                maxLines = Int.MAX_VALUE
            )
        }
    }
}
