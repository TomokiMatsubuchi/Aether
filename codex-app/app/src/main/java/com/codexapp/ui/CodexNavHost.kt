package com.codexapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.codexapp.network.CodexClient
import com.codexapp.network.ServerManager
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.codexapp.network.Session
import com.codexapp.ui.screens.ChatScreen
import com.codexapp.ui.screens.NewSessionDialog
import com.codexapp.ui.screens.DiffViewerScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import com.codexapp.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexNavHost(serverManager: ServerManager, codexClient: CodexClient) {
    val isServerReady by serverManager.isReady.collectAsState()
    val statusMessage by serverManager.statusMessage.collectAsState()

    if (!isServerReady) {
        // Loading screen while server starts
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Aether",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    statusMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val sessions by codexClient.sessions.collectAsState()
    val currentSessionId by codexClient.currentSessionId.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(isServerReady) {
        if (isServerReady) {
            val prefs = context.getSharedPreferences("codex_prefs", android.content.Context.MODE_PRIVATE)
            val savedUrl = prefs.getString("server_url", "http://127.0.0.1:3000") ?: "http://127.0.0.1:3000"
            codexClient.updateServerUrl(savedUrl)
            codexClient.checkConnection()
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewDialog by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf("chat") }

    if (showNewDialog) {
        NewSessionDialog(
            codexClient = codexClient,
            onDismiss = { showNewDialog = false },
            onCreate = { title, workdir ->
                codexClient.newSession(title = title, workdir = workdir)
                showNewDialog = false
            }
        )
    }

    if (currentScreen == "diff") {
        DiffViewerScreen(
            codexClient = codexClient,
            workdir = codexClient.getCurrentWorkdir(),
            onBack = { currentScreen = "chat" }
        )
        return
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(
                        modifier = Modifier.width(300.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Column(modifier = Modifier.fillMaxHeight()) {
                            // Upper content - take maximum space
                            Column(modifier = Modifier.weight(1f)) {
                                Column(
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.sessions),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        stringResource(
                                            if (sessions.size == 1) R.string.sessions_count else R.string.sessions_count_plural,
                                            sessions.size
                                        ),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(Modifier.height(8.dp))

                                TextButton(
                                    onClick = {
                                        showNewDialog = true
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.new_session), fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary)
                                }

                                Spacer(Modifier.height(8.dp))

                                if (sessions.isEmpty()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.no_sessions_yet),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        items(sessions) { session ->
                                            SessionDrawerItem(
                                                session = session,
                                                isSelected = session.id == currentSessionId,
                                                onClick = {
                                                    codexClient.switchSession(session.id)
                                                    scope.launch { drawerState.close() }
                                                },
                                                onDelete = {
                                                    codexClient.deleteSession(session.id)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Lower content - Server Settings pushed to the bottom
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            ServerConfigSection(codexClient = codexClient)
                        }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ChatScreen(
                    codexClient = codexClient,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onNewSession = {
                        showNewDialog = true
                    },
                    onOpenDiffViewer = {
                        currentScreen = "diff"
                    }
                )
            }
        }
    }
}

@Composable
fun SessionDrawerItem(
    session: Session,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val projectName = remember(session.workdir) { File(session.workdir).name }
    var showDelete by remember { mutableStateOf(false) }

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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else MaterialTheme.colorScheme.surface,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    session.title,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        stringResource(R.string.delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder,
                    null,
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    projectName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                dateFormat.format(Date(session.timestamp)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ServerConfigSection(codexClient: CodexClient, modifier: Modifier = Modifier) {
    val serverUrl by codexClient.serverUrl.collectAsState()
    val isConnected by codexClient.isConnected.collectAsState()
    
    var urlInput by remember { mutableStateOf(serverUrl) }
    
    LaunchedEffect(serverUrl) {
        urlInput = serverUrl
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                    )
                    Text(
                        text = if (isConnected) stringResource(R.string.connected) else stringResource(R.string.disconnected),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                }
                
                IconButton(
                    onClick = { codexClient.checkConnection() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.check_connection),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.server_url), fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                trailingIcon = {
                    if (urlInput != serverUrl) {
                        IconButton(
                            onClick = {
                                codexClient.updateServerUrl(urlInput)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.apply),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

