package com.codexapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.network.AgentConfig
import com.codexapp.network.CodexClient
import androidx.compose.ui.res.stringResource
import com.codexapp.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigScreen(codexClient: CodexClient, onBack: () -> Unit) {
    val config by codexClient.agentConfig.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_config), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ModelSelectionCard(
                    config = config,
                    onConfigChange = { engine, model ->
                        codexClient.updateAgentConfig(config.copy(engine = engine, model = model))
                    }
                )
            }
            item {
                SystemPromptCard(config, onPromptChange = { prompt ->
                    codexClient.updateAgentConfig(config.copy(systemPrompt = prompt))
                })
            }
            item {
                ApprovalModeCard(config, onModeChange = { mode ->
                    codexClient.updateAgentConfig(config.copy(approvalMode = mode))
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionCard(
    config: AgentConfig,
    onConfigChange: (String, String) -> Unit
) {
    val engines = listOf("codex", "antigravity")
    var engineExpanded by remember { mutableStateOf(false) }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.model), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            
            Text("Engine", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            ExposedDropdownMenuBox(
                expanded = engineExpanded,
                onExpandedChange = { engineExpanded = !engineExpanded }
            ) {
                OutlinedTextField(
                    value = config.engine.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false }
                ) {
                    engines.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.uppercase()) },
                            onClick = {
                                val defaultModel = if (option == "antigravity") "gemini-2.5-flash" else "codex-ollama"
                                onConfigChange(option, defaultModel)
                                engineExpanded = false
                            }
                        )
                    }
                }
            }
            
            Text("Model", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            OutlinedTextField(
                value = config.model,
                onValueChange = { onConfigChange(config.engine, it) },
                readOnly = false,
                label = { Text("Enter Model Name") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun TemperatureCard(
    config: AgentConfig,
    onTempChange: (Float) -> Unit
) {
    var temp by remember(config.temperature) { mutableStateOf(config.temperature) }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.temperature), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("%.1f".format(temp), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = temp,
                onValueChange = { newTemp ->
                    temp = newTemp
                    onTempChange(newTemp)
                },
                valueRange = 0.0f..2.0f,
                steps = 19
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Deterministic", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text("Creative", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun MaxTokensCard(
    config: AgentConfig,
    onTokensChange: (Int) -> Unit
) {
    var tokens by remember(config.maxTokens) { mutableStateOf(config.maxTokens.toFloat()) }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FormatSize, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.max_tokens), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(tokens.roundToInt().toString(), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = tokens,
                onValueChange = { newTokens ->
                    tokens = newTokens
                    onTokensChange(newTokens.roundToInt())
                },
                valueRange = 256f..8192f,
                steps = 30
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("256", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text("8192", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun SystemPromptCard(
    config: AgentConfig,
    onPromptChange: (String) -> Unit
) {
    var prompt by remember(config.systemPrompt) { mutableStateOf(config.systemPrompt) }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.system_prompt), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { p ->
                    prompt = p
                    onPromptChange(p)
                },
                label = { Text(stringResource(R.string.system_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
                minLines = 3
            )
        }
    }
}

@Composable
fun ApprovalModeCard(
    config: AgentConfig,
    onModeChange: (AgentConfig.ApprovalMode) -> Unit
) {
    val modes = listOf(
        AgentConfig.ApprovalMode.ASK to stringResource(R.string.approval_ask),
        AgentConfig.ApprovalMode.AUTO_APPROVE to stringResource(R.string.approval_auto_approve),
        AgentConfig.ApprovalMode.AUTO_DENY to stringResource(R.string.approval_auto_deny)
    )
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.approval_mode), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (config.approvalMode == mode) 
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                                else androidx.compose.ui.graphics.Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onModeChange(mode) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = config.approvalMode == mode,
                            onClick = { onModeChange(mode) }
                        )
                        Text(label, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsCard(
    config: AgentConfig,
    onToolsChange: (List<String>) -> Unit
) {
    val allTools = listOf(
        "read" to stringResource(R.string.tool_read),
        "write" to stringResource(R.string.tool_write),
        "bash" to stringResource(R.string.tool_bash),
        "grep" to stringResource(R.string.tool_grep),
        "glob" to stringResource(R.string.tool_glob),
        "task" to stringResource(R.string.tool_task),
        "patch" to stringResource(R.string.tool_patch),
        "ls" to stringResource(R.string.tool_ls)
    )
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.enabled_tools), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allTools.forEach { (tool, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, fontSize = 14.sp)
                        Switch(
                            checked = config.enabledTools.contains(tool),
                            onCheckedChange = { checked ->
                                val newTools = if (checked) config.enabledTools + tool else config.enabledTools - tool
                                onToolsChange(newTools)
                            }
                        )
                    }
                }
            }
        }
    }
}
