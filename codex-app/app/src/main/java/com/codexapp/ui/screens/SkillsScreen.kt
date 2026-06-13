package com.codexapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.network.CodexClient
import com.codexapp.network.Skill
import androidx.compose.ui.res.stringResource
import com.codexapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    codexClient: CodexClient,
    onNewSkill: () -> Unit,
    onEditSkill: (Skill) -> Unit,
    onOpenMarketplace: () -> Unit,
    onBack: () -> Unit
) {
    val skills by codexClient.skills.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMarketplace) {
                        Icon(Icons.Default.ShoppingCart, "マーケットプレイス")
                    }
                    IconButton(onClick = onNewSkill) {
                        Icon(Icons.Default.Add, stringResource(R.string.new_btn))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (skills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Extension, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.no_skills_yet), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.skills_desc), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = onNewSkill) {
                            Text(stringResource(R.string.add_skill))
                        }
                        OutlinedButton(onClick = onOpenMarketplace) {
                            Text("マーケットプレイスから追加")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(skills) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggle = { enabled -> codexClient.toggleSkill(skill.id, enabled) },
                        onEdit = { onEditSkill(skill) },
                        onDelete = { codexClient.uninstallSkill(skill.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SkillCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_skill_title)) },
            text = { Text(stringResource(R.string.delete_skill_confirm, skill.name)) },
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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text(skill.version, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace)
                    }
                    if (skill.author.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text("by ${skill.author}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (skill.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            skill.tags.take(5).forEach { tag ->
                                Text("#$tag", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium, modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(
                        checked = skill.isEnabled,
                        onCheckedChange = onToggle
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.edit_skill), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}


