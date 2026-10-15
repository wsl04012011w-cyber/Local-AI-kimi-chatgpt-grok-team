package dev.localai.chat.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localai.chat.llm.ModelState
import dev.localai.chat.ui.theme.ErrorRed
import dev.localai.chat.ui.theme.Success
import dev.localai.chat.ui.theme.TextTertiary

@Composable
fun ModelStatusChip(state: ModelState, onClick: () -> Unit) {
    val label = when (state) {
        is ModelState.NotLoaded -> "Sem modelo"
        is ModelState.Loading -> "Carregando…"
        is ModelState.Loaded -> state.modelName.removeSuffix(".gguf")
        is ModelState.Error -> "Erro no modelo"
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is ModelState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when (state) {
                                is ModelState.Loaded -> Success
                                is ModelState.Error -> ErrorRed
                                else -> TextTertiary
                            },
                            shape = CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}
