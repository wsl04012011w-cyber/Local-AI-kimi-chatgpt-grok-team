package dev.localai.chat.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.localai.chat.llm.InferenceSettings
import dev.localai.chat.llm.KvCacheType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: InferenceSettings,
    onDismiss: () -> Unit,
    onApply: (InferenceSettings) -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Desempenho do modelo", style = MaterialTheme.typography.titleLarge)
            Text(
                "Alterar estas opções recarrega o modelo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Estado atual: FA " + (if (settings.effectiveFlashAttn) "ligada" else "desligada") +
                    " · geração " + settings.nThreads + " threads · prefill " + settings.nThreadsBatch + " threads",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // --- Contexto e threads
            SectionTitle("Contexto")
            ChipRow(
                options = InferenceSettings.CTX_OPTIONS,
                selected = draft.nCtx,
                label = { "$it" },
                onSelect = { draft = draft.copy(nCtx = it) },
            )
            Hint("Contexto maior usa mais RAM no KV cache.")

            SectionTitle("Threads: ${draft.nThreads}")
            Slider(
                value = draft.nThreads.toFloat(),
                onValueChange = { draft = draft.copy(nThreads = it.roundToInt()) },
                valueRange = 1f..maxThreads.toFloat(),
                steps = (maxThreads - 2).coerceAtLeast(0),
            )
            Hint("Em celulares, usar só os núcleos grandes costuma ser mais rápido.")

            Divider()

            // --- Atenção e KV cache
            SwitchRow(
                title = "Flash attention",
                subtitle = if (draft.kvTypeV.isQuantized) "Obrigatória com V quantizado" else "Atenção mais rápida e com menos memória",
                checked = draft.effectiveFlashAttn,
                enabled = !draft.kvTypeV.isQuantized,
                onChange = { draft = draft.copy(flashAttn = it) },
            )

            SectionTitle("KV cache · K")
            ChipRow(KvCacheType.entries, draft.kvTypeK, { it.label }) { draft = draft.copy(kvTypeK = it) }
            SectionTitle("KV cache · V")
            ChipRow(KvCacheType.entries, draft.kvTypeV, { it.label }) { draft = draft.copy(kvTypeV = it) }
            Hint(
                "KV ≈ ${(draft.kvSizeRatio * 100).roundToInt()}% da memória do F16. " +
                    "O K costuma ser mais sensível à quantização que o V.",
            )

            SwitchRow(
                title = "Reaproveitar cache entre mensagens",
                subtitle = "Não reprocessa o trecho do histórico que não mudou",
                checked = draft.reuseCache,
                onChange = { draft = draft.copy(reuseCache = it) },
            )

            Divider()

            // --- Memória
            SwitchRow(
                title = "Mapear modelo (mmap)",
                subtitle = "Desligado: carrega o modelo inteiro na RAM",
                checked = draft.useMmap,
                onChange = { draft = draft.copy(useMmap = it) },
            )
            SwitchRow(
                title = "Travar na RAM (mlock)",
                subtitle = "Evita que o Android tire o modelo da memória; pode ser negado pelo sistema",
                checked = draft.useMlock,
                onChange = { draft = draft.copy(useMlock = it) },
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = InferenceSettings() }) { Text("Restaurar padrão") }
                Button(
                    onClick = { onApply(draft); onDismiss() },
                    enabled = draft != settings,
                    modifier = Modifier.weight(1f),
                ) { Text("Aplicar e recarregar") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
