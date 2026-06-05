package com.codexapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    baseFontSize: Int = 15,
    codeBackgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
) {
    if (content.isBlank()) {
        Text(content, modifier = modifier, color = baseColor, fontSize = baseFontSize.sp)
        return
    }

    val clipboardManager = LocalClipboardManager.current
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    // Resolve theme colors at composable level
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockView(
                        code = block.code,
                        language = block.language,
                        codeBackgroundColor = codeBackgroundColor,
                        onCopy = { clipboardManager.setText(AnnotatedString(block.code)) }
                    )
                }
                is MarkdownBlock.ThoughtBlock -> {
                    ThoughtBlockView(
                        content = block.content,
                        label = block.label
                    )
                }
                is MarkdownBlock.Heading -> {
                    Text(
                        block.text,
                        fontSize = (baseFontSize + if (block.level == 1) 6 else if (block.level == 2) 4 else 2).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 4.dp)
                    )
                }
                is MarkdownBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row(modifier = Modifier.padding(start = 4.dp)) {
                                Text("•  ", color = MaterialTheme.colorScheme.primary, fontSize = baseFontSize.sp)
                                InlineMarkdownText(
                                    text = item,
                                    baseColor = baseColor,
                                    fontSize = baseFontSize,
                                    inlineCodeBg = inlineCodeBg,
                                    inlineCodeColor = inlineCodeColor,
                                    linkColor = linkColor
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    InlineMarkdownText(
                        text = block.text,
                        baseColor = baseColor,
                        fontSize = baseFontSize,
                        inlineCodeBg = inlineCodeBg,
                        inlineCodeColor = inlineCodeColor,
                        linkColor = linkColor
                    )
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ThoughtBlockView(content: String, label: String) {
    var expanded by remember { mutableStateOf(false) }

    val bgColor = when {
        label.contains("tool", ignoreCase = true) || label.contains("bash", ignoreCase = true) ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        label.contains("thinking", ignoreCase = true) || label.contains("plan", ignoreCase = true) ->
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val accentColor = when {
        label.contains("tool", ignoreCase = true) || label.contains("bash", ignoreCase = true) ->
            MaterialTheme.colorScheme.tertiary
        label.contains("thinking", ignoreCase = true) || label.contains("plan", ignoreCase = true) ->
            MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val icon = when {
        label.contains("tool", ignoreCase = true) || label.contains("bash", ignoreCase = true) ->
            Icons.Default.Build
        label.contains("thinking", ignoreCase = true) || label.contains("plan", ignoreCase = true) ->
            Icons.Default.Psychology
        else -> Icons.Default.Terminal
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(16.dp), tint = accentColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label.ifBlank { "Codex" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = accentColor.copy(alpha = 0.7f)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    content,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
fun CodeBlockView(
    code: String,
    language: String,
    codeBackgroundColor: Color,
    onCopy: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = codeBackgroundColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language.ifBlank { "code" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
                TextButton(
                    onClick = { onCopy(); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "Copied" else "Copy",
                        fontSize = 12.sp,
                        color = if (copied) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                Text(
                    code.trimEnd(),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun InlineMarkdownText(
    text: String,
    baseColor: Color,
    fontSize: Int,
    inlineCodeBg: Color,
    inlineCodeColor: Color,
    linkColor: Color,
) {
    val annotated = remember(text) {
        parseInlineMarkdown(text, baseColor, fontSize, inlineCodeBg, inlineCodeColor, linkColor)
    }
    Text(annotated, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
}

// --- Parsing ---

sealed class MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class ThoughtBlock(val content: String, val label: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
            continue
        }

        // Thought/Tool block: lines starting with ╭ or │ (box-drawing chars)
        if (line.trimStart().startsWith("╭")) {
            val topLine = line.trimStart()
            val labelMatch = Regex("╭───\\s*(.*?)\\s*───╮").find(topLine)
            val label = labelMatch?.groupValues?.get(1)?.trim() ?: ""

            val contentLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.trimStart().startsWith("╰")) break
                val cleaned = l.trimStart()
                    .removePrefix("│ ")
                    .removeSuffix(" │")
                    .removePrefix("│")
                    .removeSuffix("│")
                contentLines.add(cleaned)
                i++
            }
            i++ // skip closing ╰ line

            val content = contentLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                blocks.add(MarkdownBlock.ThoughtBlock(content, label))
            }
            continue
        }

        // Heading
        if (line.startsWith("#")) {
            val level = line.takeWhile { it == '#' }.length
            blocks.add(MarkdownBlock.Heading(line.removePrefix("#".repeat(level)).trim(), level))
            i++
            continue
        }

        // Horizontal rule
        if (line.trim().matches(Regex("^-{3,}$")) || line.trim().matches(Regex("^\\*{3,}$"))) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // Bullet list
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            val items = mutableListOf<String>()
            while (i < lines.size &&
                (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* "))
            ) {
                items.add(lines[i].trimStart().removePrefix("- ").removePrefix("* "))
                i++
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Numbered list
        if (Regex("^\\s*\\d+\\.\\s").matches(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*\\d+\\.\\s").matches(lines[i])) {
                items.add(lines[i].trimStart().replaceFirst(Regex("^\\d+\\.\\s"), ""))
                i++
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Paragraph
        if (line.isNotBlank()) {
            val paraLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith("╭") &&
                !lines[i].startsWith("#") &&
                !lines[i].trimStart().startsWith("- ") &&
                !lines[i].trimStart().startsWith("* ") &&
                !Regex("^\\s*\\d+\\.\\s").matches(lines[i]) &&
                !lines[i].trim().matches(Regex("^-{3,}$|^\\*{3,}$"))
            ) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
            continue
        }

        i++
    }

    return blocks
}

fun parseInlineMarkdown(
    text: String,
    baseColor: Color,
    fontSize: Int,
    inlineCodeBg: Color,
    inlineCodeColor: Color,
    linkColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
        val italicPattern = Regex("\\*(.+?)\\*")
        val codePattern = Regex("`([^`]+)`")
        val linkPattern = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")

        while (remaining.isNotEmpty()) {
            val matches = listOf(
                boldPattern.find(remaining)?.let { Triple(it.range.first, it.range.last + 1, "bold") },
                italicPattern.find(remaining)?.let { Triple(it.range.first, it.range.last + 1, "italic") },
                codePattern.find(remaining)?.let { Triple(it.range.first, it.range.last + 1, "code") },
                linkPattern.find(remaining)?.let { Triple(it.range.first, it.range.last + 1, "link") }
            ).filterNotNull().sortedBy { it.first }

            if (matches.isEmpty()) {
                withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Normal)) {
                    append(remaining)
                }
                break
            }

            val (start, end, type) = matches.first()
            if (start > 0) {
                withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Normal)) {
                    append(remaining.substring(0, start))
                }
            }

            val matchText = remaining.substring(start, end)
            when (type) {
                "bold" -> {
                    val inner = boldPattern.find(matchText)!!.groupValues[1]
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(inner)
                    }
                }
                "italic" -> {
                    val inner = italicPattern.find(matchText)!!.groupValues[1]
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(inner)
                    }
                }
                "code" -> {
                    val inner = codePattern.find(matchText)!!.groupValues[1]
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (fontSize - 1).sp,
                        background = inlineCodeBg,
                        color = inlineCodeColor
                    )) {
                        append(inner)
                    }
                }
                "link" -> {
                    val groups = linkPattern.find(matchText)!!.groupValues
                    withStyle(SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.Medium
                    )) {
                        append(groups[1])
                    }
                }
            }

            remaining = remaining.substring(end)
        }
    }
}
