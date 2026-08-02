package com.tenniscount.app.ui.scoreboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tenniscount.app.R
import com.tenniscount.app.score.GameState
import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.Player
import com.tenniscount.app.ui.MatchUiState
import com.tenniscount.app.ui.MatchViewModel
import com.tenniscount.app.service.MicState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ScoreboardScreen(state: MatchUiState, viewModel: MatchViewModel) {
    val match = state.matchState ?: return

    // Экран не гаснет, пока открыто табло.
    val view = LocalView.current
    LaunchedEffect(Unit) { view.keepScreenOn = true }

    if (state.finished) {
        FinishedContent(state, match, viewModel)
        return
    }

    var showGameEdit by remember { mutableStateOf(false) }
    var showSetEdit by remember { mutableStateOf(false) }
    var showSpeechSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.paused) {
            Text(
                text = stringResource(R.string.paused_label),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Player.entries.forEach { player ->
                PlayerColumn(
                    state = state,
                    match = match,
                    player = player,
                    onAddPoint = { viewModel.addPoint(player) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SetsLine(match)

        MicControl(state, viewModel)

        SignalVolumeRow(state, viewModel, onSpeechSettings = { showSpeechSettings = true })

        LogView(state.log, modifier = Modifier.heightIn(max = 96.dp).fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::undo,
                enabled = state.canUndo,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.undo)) }
            OutlinedButton(
                onClick = { showGameEdit = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.edit_game)) }
            OutlinedButton(
                onClick = { showSetEdit = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.edit_set)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::togglePause,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(if (state.paused) R.string.resume else R.string.pause),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Button(
                onClick = viewModel::finishMatch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.finish_match),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }

    if (showGameEdit) {
        GameEditDialog(
            current = match.currentSet.currentGame,
            state = state,
            onDismiss = { showGameEdit = false },
            onApply = { p1, p2 ->
                viewModel.editGameScore(p1, p2)
                showGameEdit = false
            },
        )
    }
    if (showSetEdit) {
        SetEditDialog(
            gamesP1 = match.currentSet.gamesP1,
            gamesP2 = match.currentSet.gamesP2,
            state = state,
            onDismiss = { showSetEdit = false },
            onApply = { g1, g2 ->
                viewModel.editSetScore(g1, g2)
                showSetEdit = false
            },
        )
    }
    if (showSpeechSettings) {
        SpeechSettingsDialog(state, viewModel, onDismiss = { showSpeechSettings = false })
    }
}

@Composable
private fun PlayerColumn(
    state: MatchUiState,
    match: MatchState,
    player: Player,
    onAddPoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val game = match.currentSet.currentGame
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = state.name(player),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (match.server == player) "● ${stringResource(R.string.serving)}" else " ",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = match.currentSet.games(player).toString(),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = game.displayPoints(player),
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold,
        )
        Button(
            onClick = onAddPoint,
            enabled = !state.paused,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.add_point),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SetsLine(match: MatchState) {
    val sets = match.completedSets.joinToString("   ") { it.toString() }
    Text(
        text = if (sets.isEmpty()) stringResource(R.string.sets_label)
        else stringResource(R.string.sets_label) + " " + sets,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/**
 * Управление прослушиванием: кнопка вкл/выкл, индикатор состояния микрофона,
 * последняя услышанная фраза и предупреждение о противоречии счёта.
 */
@Composable
private fun MicControl(state: MatchUiState, viewModel: MatchViewModel) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) viewModel.toggleListening()
    }

    // Микрофон обязателен; уведомление (Android 13+) — для отображения
    // foreground service, без него прослушивание всё равно работает.
    val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val listening = state.micState == MicState.LISTENING
    val busy = state.micState == MicState.DOWNLOADING || state.micState == MicState.PREPARING

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.toggleListening()
                    else permissionLauncher.launch(requiredPermissions)
                },
                enabled = !busy && !state.finished,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(if (listening) R.string.listen_stop else R.string.listen_start))
            }
            Text(
                text = micStatusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.micState) {
                    MicState.ERROR -> MaterialTheme.colorScheme.error
                    MicState.LISTENING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(2f),
            )
        }

        if (listening && state.lastHeard.isNotEmpty()) {
            Text(
                text = stringResource(R.string.heard_prefix, state.lastHeard),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.warning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            LaunchedEffect(warning) {
                delay(4_000)
                viewModel.clearWarning()
            }
        }
    }
}

/** Громкость сигналов приложения относительно медиа-громкости (поверх музыки). */
@Composable
private fun SignalVolumeRow(
    state: MatchUiState,
    viewModel: MatchViewModel,
    onSpeechSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.signal_volume),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.signalVolume,
            onValueChange = viewModel::setSignalVolume,
            onValueChangeFinished = viewModel::previewSignal,
            valueRange = 0.5f..1.5f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(state.signalVolume * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onSpeechSettings) {
            Text(stringResource(R.string.speech_settings))
        }
    }
}

/** Настройки авто-озвучки: счёт в конце гейма, сет-поинт, итог сета. */
@Composable
private fun SpeechSettingsDialog(
    state: MatchUiState,
    viewModel: MatchViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speech_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeechToggle(
                    label = stringResource(R.string.speak_game_end),
                    checked = state.speakGameEnd,
                    onCheckedChange = viewModel::setSpeakGameEnd,
                )
                SpeechToggle(
                    label = stringResource(R.string.speak_set_point),
                    checked = state.speakSetPoint,
                    onCheckedChange = viewModel::setSpeakSetPoint,
                )
                SpeechToggle(
                    label = stringResource(R.string.speak_set_end),
                    checked = state.speakSetEnd,
                    onCheckedChange = viewModel::setSpeakSetEnd,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun SpeechToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun micStatusText(state: MatchUiState): String = when (state.micState) {
    MicState.OFF -> ""
    MicState.DOWNLOADING -> state.downloadProgress
        ?.let { stringResource(R.string.model_downloading, it) }
        ?: stringResource(R.string.mic_preparing)
    MicState.PREPARING -> stringResource(R.string.mic_preparing)
    MicState.LISTENING -> stringResource(R.string.mic_listening)
    MicState.ERROR -> stringResource(R.string.mic_error, state.micError.orEmpty())
}

@Composable
private fun LogView(log: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, reverseLayout = true) {
        items(log.asReversed()) { entry ->
            Text(
                text = entry,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinishedContent(state: MatchUiState, match: MatchState, viewModel: MatchViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.match_finished),
            style = MaterialTheme.typography.headlineLarge,
        )
        val sets = match.completedSets + match.currentSet.score
        Player.entries.forEach { player ->
            Text(
                text = state.name(player) + "  —  " +
                    sets.count { it.winner == player } + " " + stringResource(R.string.sets_label).dropLast(1).lowercase(),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = sets.joinToString("   ") { it.toString() },
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Button(
            onClick = viewModel::newMatch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.new_match),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun GameEditDialog(
    current: GameState,
    state: MatchUiState,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit,
) {
    var p1 by remember { mutableIntStateOf(current.pointsP1) }
    var p2 by remember { mutableIntStateOf(current.pointsP2) }

    val options = listOf("0" to 0, "15" to 1, "30" to 2, "40" to 3, "AD" to 4)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_game_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Player.entries.forEach { player ->
                    Text(state.name(player), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { (label, value) ->
                            val selected = if (player == Player.ONE) p1 == value else p2 == value
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (player == Player.ONE) p1 = value else p2 = value
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(p1, p2) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SetEditDialog(
    gamesP1: Int,
    gamesP2: Int,
    state: MatchUiState,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit,
) {
    var g1 by remember { mutableIntStateOf(gamesP1) }
    var g2 by remember { mutableIntStateOf(gamesP2) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Player.entries.forEach { player ->
                    val value = if (player == Player.ONE) g1 else g2
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            state.name(player),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            if (player == Player.ONE) g1 = (g1 - 1).coerceAtLeast(0)
                            else g2 = (g2 - 1).coerceAtLeast(0)
                        }) { Text("−") }
                        Text(
                            value.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(onClick = {
                            if (player == Player.ONE) g1++ else g2++
                        }) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(g1, g2) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
