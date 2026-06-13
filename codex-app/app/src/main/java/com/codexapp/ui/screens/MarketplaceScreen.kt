package com.codexapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.R
import com.codexapp.network.CodexClient
import com.codexapp.network.Skill
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    codexClient: CodexClient,
    onBack: () -> Unit
) {
    val localSkills by codexClient.skills.collectAsState()
    var marketplaceSkills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        try {
            marketplaceSkills = codexClient.getMarketplaceSkills()
        } catch (_: Exception) {}
        isLoading = false
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUrlDialog = false },
            title = { Text("URLからスキルを追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YAMLフロントマターを含む SKILL.md の raw URL を入力してください。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://raw.githubusercontent.com/...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isDownloading
                    )
                    if (isDownloading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("ダウンロード中...", fontSize = 12.sp)
                        }
                    }
                    if (errorMessage != null) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (urlInput.isBlank()) return@Button
                        isDownloading = true
                        errorMessage = null
                        codexClient.importSkillFromUrl(
                            urlStr = urlInput,
                            onSuccess = { skill ->
                                isDownloading = false
                                codexClient.installSkill(skill)
                                scope.launch {
                                    snackbarHostState.showSnackbar("スキル \"${skill.name}\" をインストールしました。")
                                }
                                showUrlDialog = false
                                urlInput = ""
                            },
                            onError = { err ->
                                isDownloading = false
                                errorMessage = err
                            }
                        )
                    },
                    enabled = !isDownloading && urlInput.isNotBlank()
                ) {
                    Text("追加")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUrlDialog = false; urlInput = ""; errorMessage = null },
                    enabled = !isDownloading
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("マーケットプレイス", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Default.Download, "URLからインポート")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (marketplaceSkills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Extension, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text("利用可能なスキルがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showUrlDialog = true }) {
                        Text("URLからインポート")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(marketplaceSkills) { skill ->
                    val isInstalled = localSkills.any { it.name == skill.name || it.id == skill.id }
                    MarketplaceSkillCard(
                        skill = skill,
                        isInstalled = isInstalled,
                        onInstall = {
                            codexClient.installSkill(skill)
                            scope.launch {
                                snackbarHostState.showSnackbar("スキル \"${skill.name}\" をインストールしました。")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MarketplaceSkillCard(
    skill: Skill,
    isInstalled: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text(skill.version, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                    }
                    if (skill.author.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text("by ${skill.author}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(skill.description, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (skill.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            skill.tags.take(5).forEach { tag ->
                                Text("#$tag", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium, modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    if (isInstalled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, "インストール済み", tint = MaterialTheme.colorScheme.primary)
                            Text("追加済み", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(onClick = onInstall, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("追加", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
