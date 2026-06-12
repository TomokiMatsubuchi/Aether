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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.R
import com.codexapp.network.CodexClient
import com.codexapp.network.GitFile
import com.codexapp.network.GitStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffViewerScreen(
    codexClient: CodexClient,
    workdir: String,
    onBack: () -> Unit
) {
    var gitStatus by remember { mutableStateOf<GitStatus?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isInitializing by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<GitFile?>(null) }
    val scope = rememberCoroutineScope()

    fun loadStatus() {
        isLoading = true
        scope.launch {
            try {
                gitStatus = codexClient.getGitStatus(workdir)
            } catch (e: Exception) {
                gitStatus = GitStatus(isGit = false, files = emptyList())
            }
            isLoading = false
        }
    }

    LaunchedEffect(workdir) {
        loadStatus()
    }

    if (selectedFile != null) {
        FileDiffDetailView(
            codexClient = codexClient,
            workdir = workdir,
            gitFile = selectedFile!!,
            onClose = { selectedFile = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.diff_viewer_title), fontWeight = FontWeight.Bold)
                        Text(
                            workdir,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { loadStatus() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.check_connection))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (gitStatus?.isGit == false) {
                // Git not initialized view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.git_not_tracked),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.git_not_tracked_desc),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            isInitializing = true
                            scope.launch {
                                val success = codexClient.gitInit(workdir)
                                if (success) {
                                    loadStatus()
                                }
                                isInitializing = false
                            }
                        },
                        enabled = !isInitializing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isInitializing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.git_initializing))
                        } else {
                            Icon(Icons.Default.History, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.git_init_btn))
                        }
                    }
                }
            } else {
                val files = gitStatus?.files ?: emptyList()
                if (files.isEmpty()) {
                    // Empty changes view
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_changes),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_changes_desc),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { file ->
                            GitFileCard(
                                file = file,
                                onClick = { selectedFile = file }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GitFileCard(
    file: GitFile,
    onClick: () -> Unit
) {
    val statusColor = when (file.status) {
        "M", "MM" -> MaterialTheme.colorScheme.primary
        "A", "??" -> Color(0xFF4CAF50)
        "D" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    
    val statusText = when (file.status) {
        "M", "MM" -> stringResource(R.string.file_modified_tag)
        "A", "??" -> stringResource(R.string.file_added_tag)
        "D" -> stringResource(R.string.file_deleted_tag)
        else -> file.status
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.path.substringAfterLast("/"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (file.path.contains("/")) {
                    Text(
                        text = file.path.substringBeforeLast("/"),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SuggestionChip(
                onClick = {},
                label = { Text(statusText, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = statusColor.copy(alpha = 0.15f),
                    labelColor = statusColor
                ),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDiffDetailView(
    codexClient: CodexClient,
    workdir: String,
    gitFile: GitFile,
    onClose: () -> Unit
) {
    var diffContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(gitFile) {
        isLoading = true
        try {
            diffContent = codexClient.getGitDiff(workdir, gitFile.path)
        } catch (_: Exception) {
            diffContent = ""
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(gitFile.path.substringAfterLast("/"), fontWeight = FontWeight.Bold)
                        Text(
                            gitFile.path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (diffContent.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No diff details available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val lines = remember(diffContent) { diffContent.split("\n") }
                val scrollState = rememberScrollState()
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E)) // Dark code editor background
                ) {
                    items(lines) { line ->
                        val (bgColor, textColor) = when {
                            line.startsWith("+") && !line.startsWith("+++") -> 
                                Pair(Color(0xFF2E7D32).copy(alpha = 0.25f), Color(0xFFA5D6A7)) // Add line
                            line.startsWith("-") && !line.startsWith("---") -> 
                                Pair(Color(0xFFC62828).copy(alpha = 0.25f), Color(0xFFEF9A9A)) // Remove line
                            line.startsWith("@@") -> 
                                Pair(Color(0xFF00838F).copy(alpha = 0.15f), Color(0xFF80DEEA)) // Section line
                            line.startsWith("diff") -> 
                                Pair(Color(0xFF37474F).copy(alpha = 0.4f), Color(0xFFCFD8DC)) // Git diff header line
                            else -> 
                                Pair(Color.Transparent, Color(0xFFE0E0E0)) // Unchanged line
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .horizontalScroll(scrollState)
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.widthIn(min = 600.dp) // ensure long lines don't get squeezed
                            )
                        }
                    }
                }
            }
        }
    }
}
