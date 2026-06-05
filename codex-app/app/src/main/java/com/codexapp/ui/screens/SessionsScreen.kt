package com.codexapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.network.CodexClient
import com.codexapp.network.Session
import com.codexapp.network.WorkspaceDir
import androidx.compose.ui.res.stringResource
import com.codexapp.R
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val codexClient = remember { CodexClient(ctx) }
    val sessions by codexClient.sessions.collectAsState()

    var showNewDialog by remember { mutableStateOf(false) }

    if (showNewDialog) {
        NewSessionDialog(
            codexClient = codexClient,
            onDismiss = { showNewDialog = false },
            onCreate = { title, workdir ->
                codexClient.newSession(title = title, workdir = workdir)
                showNewDialog = false
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showNewDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.new_btn))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubble, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.no_sessions_yet), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                }
            }
        } else {
            val currentSessionId by codexClient.currentSessionId.collectAsState()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    val isActive = session.id == currentSessionId
                    SessionCard(session, isActive,
                        onClick = { codexClient.switchSession(session.id); onBack() },
                        onDelete = { codexClient.deleteSession(session.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun NewSessionDialog(
    codexClient: CodexClient,
    onDismiss: () -> Unit,
    onCreate: (title: String, workdir: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf<WorkspaceDir?>(null) }
    var selectedPath by remember { mutableStateOf("/root/workspace") }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Initial load
    LaunchedEffect(Unit) {
        try {
            val dir = codexClient.browseWorkspace(null)
            currentDir = dir
            selectedPath = dir.path
        } catch (_: Exception) {
            currentDir = null
        }
        isLoading = false
    }

    fun navigateTo(path: String) {
        isLoading = true
        scope.launch {
            try {
                val dir = codexClient.browseWorkspace(path)
                currentDir = dir
                selectedPath = dir.path
            } catch (_: Exception) {
                currentDir = null
            }
            isLoading = false
        }
    }

    fun navigateUp() {
        val parent = File(currentDir?.path ?: "/").parent ?: "/"
        navigateTo(parent)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.new_session), fontWeight = FontWeight.Bold)
                if (currentDir != null) {
                    Text(
                        currentDir!!.path,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.session_name_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.select_project), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))

                // Current directory indicator with "Select here" button
                // Current directory indicator with Up button
                if (currentDir != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { navigateUp() }) {
                            Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.up), fontSize = 12.sp)
                        }
                    }
                }

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else if (currentDir != null) {
                    if (currentDir!!.subdirs.isEmpty()) {
                        Text(stringResource(R.string.no_subdirectories), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(currentDir!!.subdirs) { subdir ->
                                val isSelected = selectedPath == subdir.path
                                Surface(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { navigateTo(subdir.path) },
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            else MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Default.FolderOpen else Icons.Default.Folder,
                                            null, Modifier.size(20.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(subdir.name, fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                        Spacer(Modifier.weight(1f))
                                        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val defaultSessionTitle = stringResource(R.string.new_session)
            TextButton(onClick = { onCreate(title.ifBlank { defaultSessionTitle }, selectedPath) }) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun SessionCard(session: Session, isActive: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    var showDelete by remember { mutableStateOf(false) }
    val projectName = remember(session.workdir) { File(session.workdir).name }
    val parentPath = remember(session.workdir) { File(session.workdir).parent ?: "/" }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(session.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.active), fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                border = null,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Project name with folder icon
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    // Parent path in smaller text
                    Text(parentPath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text(session.lastMessage, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(dateFormat.format(Date(session.timestamp)),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
