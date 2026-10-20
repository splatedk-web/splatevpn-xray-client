package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/**
 * Lightweight, zero-dependency Compose Markdown Renderer tailored for changelogs,
 * release notes, and documentation. Supports headings, bold/italic formatting,
 * code blocks, inline tags, bullet/numbered lists, alerts, and dividers.
 */
@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val lines = remember(markdown) { markdown.lines() }

    CompositionLocalProvider(
        LocalContentColor provides colors.textPrimary
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            var inCodeBlock = false
            val codeBlockLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trimEnd()

                // Handle code blocks ```
                if (trimmed.startsWith("```")) {
                    if (inCodeBlock) {
                        // Flush code block
                        CodeBlock(code = codeBlockLines.joinToString("\n"))
                        codeBlockLines.clear()
                        inCodeBlock = false
                    } else {
                        inCodeBlock = true
                    }
                    continue
                }

                if (inCodeBlock) {
                    codeBlockLines.add(line)
                    continue
                }

                val trimmedStart = trimmed.trimStart()

                when {
                    trimmedStart.isEmpty() -> {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    // Horizontal divider
                    trimmedStart == "---" || trimmedStart == "***" || trimmedStart == "___" -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            thickness = 1.dp,
                            color = colors.glassBorderLight.copy(alpha = 0.25f)
                        )
                    }
                    // Heading 1 (# ...)
                    trimmedStart.startsWith("# ") -> {
                        val title = trimmedStart.removePrefix("# ").trim()
                        HeadingView(title = title, level = 1)
                    }
                    // Heading 2 (## ...)
                    trimmedStart.startsWith("## ") -> {
                        val title = trimmedStart.removePrefix("## ").trim()
                        HeadingView(title = title, level = 2)
                    }
                    // Heading 3 (### ...)
                    trimmedStart.startsWith("### ") -> {
                        val title = trimmedStart.removePrefix("### ").trim()
                        HeadingView(title = title, level = 3)
                    }
                    // Heading 4 (#### ...)
                    trimmedStart.startsWith("#### ") -> {
                        val title = trimmedStart.removePrefix("#### ").trim()
                        HeadingView(title = title, level = 4)
                    }
                    // Blockquote or Callout Alert (> ...)
                    trimmedStart.startsWith("> ") -> {
                        val content = trimmedStart.removePrefix("> ").trim()
                        AlertCalloutView(content = content)
                    }
                    // Bullet item (* ..., - ..., • ...)
                    trimmedStart.startsWith("* ") || trimmedStart.startsWith("- ") || trimmedStart.startsWith("• ") -> {
                        val bulletText = trimmedStart.substring(2).trim()
                        val indentLevel = (trimmed.length - trimmedStart.length) / 2
                        BulletItemView(text = bulletText, indentLevel = indentLevel)
                    }
                    // Numbered list item (1. ..., 2. ...)
                    trimmedStart.matches(Regex("""^\d+\.\s.*""")) -> {
                        val match = Regex("""^(\d+)\.\s(.*)""").find(trimmedStart)
                        if (match != null) {
                            val number = match.groupValues[1]
                            val text = match.groupValues[2]
                            NumberedItemView(number = number, text = text)
                        } else {
                            StandardParagraph(text = trimmedStart)
                        }
                    }
                    else -> {
                        StandardParagraph(text = trimmedStart)
                    }
                }
            }

            // Flush any remaining code block
            if (inCodeBlock && codeBlockLines.isNotEmpty()) {
                CodeBlock(code = codeBlockLines.joinToString("\n"))
            }
        }
    }
}

@Composable
private fun HeadingView(title: String, level: Int) {
    val colors = LocalAppThemeColors.current

    val (fontSize, fontWeight, color, topPadding) = when (level) {
        1 -> Quadruple(17.sp, FontWeight.Black, colors.accentCyan, 8.dp)
        2 -> Quadruple(15.sp, FontWeight.Bold, colors.textPrimary, 6.dp)
        3 -> Quadruple(13.5.sp, FontWeight.SemiBold, colors.accentEmerald, 4.dp)
        else -> Quadruple(12.5.sp, FontWeight.SemiBold, colors.textPrimary, 2.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(fontSize.value.dp * 0.9f)
                .clip(RoundedCornerShape(1.5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(color, color.copy(alpha = 0.3f))
                    )
                )
        )
        Text(
            text = renderMarkdownInline(title, color, colors.textPrimary, colors.accentCyan),
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = (fontSize.value * 1.35f).sp
        )
    }
}

@Composable
private fun BulletItemView(text: String, indentLevel: Int) {
    val colors = LocalAppThemeColors.current
    val indentPadding = (indentLevel * 12).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPadding, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(colors.accentCyan.copy(alpha = 0.85f))
        )
        Text(
            text = renderMarkdownInline(
                raw = text,
                textColor = colors.textSecondary,
                boldColor = colors.textPrimary,
                codeColor = colors.accentCyan
            ),
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.5.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumberedItemView(number: String, text: String) {
    val colors = LocalAppThemeColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(colors.accentCyan.copy(alpha = 0.15f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = number,
                color = colors.accentCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = renderMarkdownInline(
                raw = text,
                textColor = colors.textSecondary,
                boldColor = colors.textPrimary,
                codeColor = colors.accentCyan
            ),
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.5.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AlertCalloutView(content: String) {
    val colors = LocalAppThemeColors.current
    val isImportant = content.contains("[!IMPORTANT]", ignoreCase = true) ||
            content.contains("[!WARNING]", ignoreCase = true)
    val isTip = content.contains("[!TIP]", ignoreCase = true)

    val stripeColor = when {
        isImportant -> colors.accentCrimson
        isTip -> colors.accentEmerald
        else -> colors.accentAmber
    }

    val cleanContent = content
        .replace(Regex("""\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*""", RegexOption.IGNORE_CASE), "")
        .trim()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(stripeColor.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = stripeColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isImportant) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = stripeColor,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(15.dp)
            )
            Text(
                text = renderMarkdownInline(
                    raw = cleanContent,
                    textColor = colors.textPrimary,
                    boldColor = colors.textPrimary,
                    codeColor = colors.accentCyan
                ),
                color = colors.textPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val colors = LocalAppThemeColors.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (colors.isDark) colors.surfaceElevated.copy(alpha = 0.90f)
                else colors.backgroundDeep
            )
            .border(
                width = 1.dp,
                color = colors.glassBorderLight.copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text = code,
            color = colors.accentCyan,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun StandardParagraph(text: String) {
    val colors = LocalAppThemeColors.current

    Text(
        text = renderMarkdownInline(
            raw = text,
            textColor = colors.textSecondary,
            boldColor = colors.textPrimary,
            codeColor = colors.accentCyan
        ),
        color = colors.textSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.5.sp
    )
}

/**
 * Fast regex-based parser that converts Markdown inline tokens (`**bold**`, `*italic*`, `` `code` ``)
 * into a rich Compose AnnotatedString.
 */
fun renderMarkdownInline(
    raw: String,
    textColor: Color,
    boldColor: Color,
    codeColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(color = textColor)) {
            var cursor = 0
            val tokenRegex = Regex("""(\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)\]\((.+?)\))""")
            val matches = tokenRegex.findAll(raw)

            for (match in matches) {
                // Append plain text before match
                if (match.range.first > cursor) {
                    append(raw.substring(cursor, match.range.first))
                }

                val fullMatch = match.value
                when {
                    // Bold + Italic (***text***)
                    fullMatch.startsWith("***") && fullMatch.endsWith("***") -> {
                        val inner = fullMatch.removeSurrounding("***")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = boldColor)) {
                            append(inner)
                        }
                    }
                    // Bold (**text**)
                    fullMatch.startsWith("**") && fullMatch.endsWith("**") -> {
                        val inner = fullMatch.removeSurrounding("**")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)) {
                            append(inner)
                        }
                    }
                    // Italic (*text*)
                    fullMatch.startsWith("*") && fullMatch.endsWith("*") -> {
                        val inner = fullMatch.removeSurrounding("*")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = textColor)) {
                            append(inner)
                        }
                    }
                    // Inline code (`code`)
                    fullMatch.startsWith("`") && fullMatch.endsWith("`") -> {
                        val inner = fullMatch.removeSurrounding("`")
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = codeColor,
                                background = codeColor.copy(alpha = 0.15f)
                            )
                        ) {
                            append(" $inner ")
                        }
                    }
                    // Link ([text](url))
                    fullMatch.startsWith("[") && fullMatch.contains("](") -> {
                        val linkMatch = Regex("""\[(.+?)\]\((.+?)\)""").find(fullMatch)
                        val label = linkMatch?.groupValues?.get(1) ?: fullMatch
                        withStyle(SpanStyle(color = codeColor, fontWeight = FontWeight.SemiBold)) {
                            append(label)
                        }
                    }
                    else -> {
                        append(fullMatch)
                    }
                }

                cursor = match.range.last + 1
            }

            // Append remainder
            if (cursor < raw.length) {
                append(raw.substring(cursor))
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
