package dev.localai.chat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
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
import dev.localai.chat.ui.chat.ChatMessage
import dev.localai.chat.ui.chat.Role
import dev.localai.chat.ui.theme.AccentDeep
import dev.localai.chat.ui.theme.AccentStrong

@Composable
fun ChatBubble(
    message: ChatMessage,
    onToggleThinking: (() -> Unit)? = null,
) {
    if (message.role == Role.USER) {
        UserBubble(message.content)
    } else {
        AssistantMessage(message, onToggleThinking)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(Brush.linearGradient(listOf(AccentStrong, AccentDeep)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, onToggleThinking: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Caixa de raciocínio: fica no chat (minimizada) depois que o modelo termina de pensar
            if (message.thinkingContent.isNotEmpty() || message.isThinking) {
                ThinkingCard(
                    content = message.thinkingContent,
                    isActive = message.isThinking,
                    expanded = message.isThinking || message.showThinking,
                    onToggle = onToggleThinking,
                )
            }

            if (message.content.isNotEmpty()) {
                if (message.isStreaming) {
                    // Texto simples durante a geração evita parse de markdown em cada publicação.
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                } else {
                    SelectionContainer {
                        MessageContent(message.content)
                    }
                }
            }

            if (message.isStreaming && !message.isThinking) {
                PulsingDot(MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

@Composable
private fun ThinkingCard(
    content: String,
    isActive: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(12.dp)
    val canToggle = onToggle != null && content.isNotEmpty() && !isActive
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (canToggle) Modifier.clickable(onClick = onToggle!!) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isActive) {
                PulsingDot(MaterialTheme.colorScheme.primary)
            } else {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (isActive) "Pensando…" else "Raciocínio",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (content.isNotEmpty() && !isActive) {
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Recolher raciocínio" else "Expandir raciocínio",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && content.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val scroll = rememberScrollState()
            // Enquanto pensa, acompanha o final do texto dentro da própria caixa
            LaunchedEffect(content.length, isActive) {
                if (isActive) scroll.scrollTo(scroll.maxValue)
            }
            Text(
                text = content.trim(),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Renderização leve de markdown: blocos de código (```), **negrito**, `código`, listas e títulos.
// ---------------------------------------------------------------------------------------------

private sealed interface Block {
    data class Prose(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
}

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    var i = 0
    while (i < src.length) {
        val open = src.indexOf("```", i)
        if (open < 0) {
            out += Block.Prose(src.substring(i))
            break
        }
        if (open > i) out += Block.Prose(src.substring(i, open))
        val nl = src.indexOf('\n', open + 3)
        if (nl < 0) {   // cerca ainda sem quebra de linha (streaming)
            out += Block.Code(src.substring(open + 3).trim(), "")
            break
        }
        val lang = src.substring(open + 3, nl).trim()
        val close = src.indexOf("```", nl + 1)
        if (close < 0) {   // bloco ainda aberto (streaming)
            out += Block.Code(lang, src.substring(nl + 1).trimEnd('\n'))
            break
        }
        out += Block.Code(lang, src.substring(nl + 1, close).trimEnd('\n'))
        i = close + 3
        if (i < src.length && src[i] == '\n') i++
    }
    return out.filter { it !is Block.Prose || it.text.isNotBlank() }
}

private val HEADING_REGEX = Regex("(?m)^#{1,6}\\s+(.+)$")
private val LIST_REGEX = Regex("(?m)^(\\s*)[-*] ")

private fun markdownInline(raw: String, codeBg: Color, codeFg: Color): AnnotatedString {
    val src = raw
        .replace(HEADING_REGEX, "**\$1**")
        .replace(LIST_REGEX, "\$1• ")
    return buildAnnotatedString {
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(src.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append(src[i]); i++
                    }
                }
                src[i] == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg, fontSize = 14.sp),
                        ) { append(src.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(src[i]); i++
                    }
                }
                else -> {
                    val bold = src.indexOf("**", i).let { if (it < 0) src.length else it }
                    val code = src.indexOf('`', i).let { if (it < 0) src.length else it }
                    val next = minOf(bold, code)
                    if (next > i) {
                        append(src.substring(i, next))
                        i = next
                    } else {
                        append(src[i])
                        i++
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageContent(content: String) {
    val blocks = remember(content) { parseBlocks(content) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeFg = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Prose -> Text(
                    text = remember(block.text) { markdownInline(block.text.trim('\n'), codeBg, codeFg) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                is Block.Code -> CodeBlock(block.lang, block.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        if (lang.isNotEmpty()) {
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onBackground,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}
