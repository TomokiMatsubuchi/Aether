package com.codexapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codexapp.network.PermissionRequest
import com.codexapp.R

@Composable
fun PermissionDialog(
    request: PermissionRequest,
    onRespond: (granted: Boolean, remember: Boolean) -> Unit
) {
    val icon = when (request.type) {
        PermissionRequest.PermissionType.FILE_READ -> Icons.Default.Visibility
        PermissionRequest.PermissionType.FILE_WRITE -> Icons.Default.Edit
        PermissionRequest.PermissionType.SHELL_COMMAND -> Icons.Default.Terminal
        PermissionRequest.PermissionType.NETWORK_REQUEST -> Icons.Default.Cloud
        PermissionRequest.PermissionType.GIT_OPERATION -> Icons.Default.Source
        PermissionRequest.PermissionType.PACKAGE_INSTALL -> Icons.Default.Download
        PermissionRequest.PermissionType.SYSTEM_ACCESS -> Icons.Default.Settings
    }

    val color = when (request.type) {
        PermissionRequest.PermissionType.FILE_READ -> MaterialTheme.colorScheme.primary
        PermissionRequest.PermissionType.FILE_WRITE -> MaterialTheme.colorScheme.secondary
        PermissionRequest.PermissionType.SHELL_COMMAND -> MaterialTheme.colorScheme.tertiary
        PermissionRequest.PermissionType.NETWORK_REQUEST -> MaterialTheme.colorScheme.error
        PermissionRequest.PermissionType.GIT_OPERATION -> Color(0xFF10B981)
        PermissionRequest.PermissionType.PACKAGE_INSTALL -> Color(0xFFF59E0B)
        PermissionRequest.PermissionType.SYSTEM_ACCESS -> Color(0xFF8B5CF6)
    }

    var showDetails by remember { mutableStateOf(false) }
    var rememberChoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, Modifier.size(24.dp), tint = color)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(request.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(request.type.name.replace("_", " "), fontSize = 12.sp, color = color,
                        fontWeight = FontWeight.Medium)
                }
            }
        },
        text = {
            Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.description, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)

                if (showDetails) {
                    val details = request.details.toString(2)
                    if (details != "{}") {
                        Text(
                            text = details,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .clip(RoundedCornerShape(8.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }

                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (showDetails) "詳細を隠す ▲" else "詳細を表示 ▼",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { onRespond(false, rememberChoice) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.deny))
                    }
                    TextButton(
                        onClick = { onRespond(false, true) }
                    ) {
                        Text(stringResource(R.string.always_deny), color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { onRespond(true, rememberChoice) }
                    ) {
                        Text(stringResource(R.string.allow))
                    }
                    TextButton(
                        onClick = { onRespond(true, true) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.always_allow))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Text("次回から同じ選択を適用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = null
    )
}
