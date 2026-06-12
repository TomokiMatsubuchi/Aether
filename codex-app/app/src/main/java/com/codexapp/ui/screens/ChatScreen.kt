package com.codexapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.network.CodexClient
import com.codexapp.ui.components.MarkdownText
import androidx.compose.ui.res.stringResource
import com.codexapp.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    codexClient: CodexClient,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenDiffViewer: () -> Unit,
) {
    val context = LocalContext.current
    val messages by codexClient.messages.collectAsState()
    val isLoading by codexClient.isLoading.collectAsState()
    val streamText by codexClient.streamText.collectAsState()
    val sessions by codexClient.sessions.collectAsState()
    val currentSessionId by codexClient.currentSessionId.collectAsState()
    val currentWorkdir = remember(sessions, messages, currentSessionId) { codexClient.getCurrentWorkdir() }
    
    val currentSession = remember(sessions, currentSessionId) {
        sessions.find { it.id == currentSessionId }
    }
    val currentEngine = currentSession?.engine ?: "codex"
    var showEngineMenu by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        selectedImageUri = uri
    }

    fun sendMessage() {
        if ((inputText.isNotBlank() || selectedImageUri != null) && !isLoading) {
            val text = inputText.trim()
            val imageUri = selectedImageUri
            inputText = ""
            selectedImageUri = null
            scope.launch {
                val localPath = imageUri?.let { codexClient.copyImageToLocalStorage(it) }
                codexClient.sendMessage(text, localPath)
            }
        }
    }

    LaunchedEffect(currentEngine) {
        if (currentEngine == "antigravity") {
            selectedImageUri = null
        }
    }

    // Auto-scroll to bottom when messages or stream changes
    LaunchedEffect(messages.size, streamText) {
        if (messages.isNotEmpty() || streamText.isNotBlank()) {
            listState.animateScrollToItem(
                // If streaming, we have one extra item (the streaming bubble)
                messages.size + if (streamText.isNotBlank()) 1 else 0
            )
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showEngineMenu = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Aether",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (currentEngine == "antigravity")
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (currentEngine == "antigravity") "Antigravity" else "Codex",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentEngine == "antigravity")
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    else
                                        MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                        }
                        Text(
                            currentWorkdir,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        DropdownMenu(
                            expanded = showEngineMenu,
                            onDismissRequest = { showEngineMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Codex") },
                                onClick = {
                                    showEngineMenu = false
                                    codexClient.changeSessionEngine(currentSessionId, "codex")
                                },
                                leadingIcon = { Icon(Icons.Default.Psychology, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Antigravity") },
                                onClick = {
                                    showEngineMenu = false
                                    codexClient.changeSessionEngine(currentSessionId, "antigravity")
                                },
                                leadingIcon = { Icon(Icons.Default.Terminal, null) }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiffViewer) {
                        Icon(Icons.Default.Difference, stringResource(R.string.diff_viewer_title))
                    }
                    IconButton(onClick = onNewSession) {
                        Icon(Icons.Default.Add, stringResource(R.string.new_session))
                    }
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.History, stringResource(R.string.sessions))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (selectedImageUri != null) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp, bottom = 8.dp)
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            coil.compose.AsyncImage(
                                model = selectedImageUri,
                                contentDescription = stringResource(R.string.selected_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            IconButton(
                                onClick = { selectedImageUri = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    stringResource(R.string.remove_image),
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Stop button while loading
                        if (isLoading) {
                            IconButton(
                                onClick = { codexClient.cancelStream() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    stringResource(R.string.stop),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        } else if (currentEngine != "antigravity") {
                            IconButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate,
                                    stringResource(R.string.select_image),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.message_aether_placeholder),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Default
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() })
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = (inputText.isNotBlank() || selectedImageUri != null) && !isLoading,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if ((inputText.isNotBlank() || selectedImageUri != null) && !isLoading)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    stringResource(R.string.send),
                                    tint = if (inputText.isNotBlank() || selectedImageUri != null) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty() && streamText.isBlank()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    // Modern Cyber Gradient Glowing Logo
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            )
                            .padding(2.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            Text(
                                "</>",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.aether_studio),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.height(4.dp))
                    // Monospace premium workdir badge
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                null,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                currentWorkdir,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.app_tagline),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Spacer(Modifier.height(36.dp))

                    // Premium Quick Action Suggesters
                    Text(
                        stringResource(R.string.quick_actions),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            icon = Icons.Default.FolderOpen,
                            title = stringResource(R.string.browse_project),
                            description = stringResource(R.string.browse_project_desc),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onOpenDrawer()
                            }
                        )
                        QuickActionCard(
                            icon = Icons.Default.Search,
                            title = stringResource(R.string.ask_aether),
                            description = stringResource(R.string.ask_aether_desc),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                inputText = context.getString(R.string.ask_aether_prompt)
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.write_code),
                            description = stringResource(R.string.write_code_desc),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                inputText = context.getString(R.string.write_code_prompt)
                            }
                        )
                        QuickActionCard(
                            icon = Icons.Default.Build,
                            title = stringResource(R.string.optimize),
                            description = stringResource(R.string.optimize_desc),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                inputText = context.getString(R.string.optimize_prompt)
                            }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, isStreaming = false)
                }

                // Streaming bubble
                if (streamText.isNotBlank()) {
                    item(key = "streaming") {
                        MessageBubble(
                            message = com.codexapp.network.ChatMessage(
                                role = "assistant",
                                content = streamText
                            ),
                            isStreaming = true
                        )
                    }
                } else if (isLoading) {
                    item(key = "thinking") {
                        ThinkingIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.aether_thinking),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Default
        )
    }
}

@Composable
fun MessageBubble(message: com.codexapp.network.ChatMessage, isStreaming: Boolean = false) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = when {
        isSystem -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val labelColor = when {
        isSystem -> MaterialTheme.colorScheme.error
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val label = when {
        isSystem -> stringResource(R.string.system_role)
        isUser -> stringResource(R.string.user_role)
        else -> stringResource(R.string.assistant_role)
    }
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                label,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = labelColor
            )
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                )
            }
        }
        Surface(
            shape = shape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            SelectionContainer {
                Column(Modifier.padding(12.dp)) {
                    if (message.imagePath != null) {
                        coil.compose.AsyncImage(
                            model = message.imagePath,
                            contentDescription = stringResource(R.string.sent_image),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 8.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }
                    if (isUser || isSystem) {
                        Text(
                            message.content,
                            fontSize = 15.sp,
                            color = if (isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 22.sp
                        )
                    } else {
                        // Assistant messages get markdown rendering
                        MarkdownText(
                            content = message.content,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            baseFontSize = 15
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(105.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
