package com.tenniscount.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tenniscount.app.R
import com.tenniscount.app.score.Player
import com.tenniscount.app.ui.MatchUiState
import com.tenniscount.app.ui.MatchViewModel

@Composable
fun MatchSetupScreen(state: MatchUiState, viewModel: MatchViewModel) {
    val tokenVisible = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineLarge,
        )

        OutlinedTextField(
            value = state.player1Name,
            onValueChange = viewModel::setPlayer1Name,
            label = { Text(stringResource(R.string.player1_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.player2Name,
            onValueChange = viewModel::setPlayer2Name,
            label = { Text(stringResource(R.string.player2_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.first_server_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Player.entries.forEach { player ->
                FilterChip(
                    selected = state.firstServer == player,
                    onClick = { viewModel.setFirstServer(player) },
                    label = {
                        Text(state.name(player), style = MaterialTheme.typography.bodyMedium)
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.telegram_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.telegramEnabled,
                onCheckedChange = viewModel::setTelegramEnabled,
            )
        }

        if (state.telegramEnabled) {
            OutlinedTextField(
                value = state.telegramToken,
                onValueChange = viewModel::setTelegramToken,
                label = { Text(stringResource(R.string.telegram_token_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = if (tokenVisible.value) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tokenVisible.value = !tokenVisible.value }) {
                        Text(if (tokenVisible.value) "Скрыть" else "Показать")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.telegramChatId,
                onValueChange = viewModel::setTelegramChatId,
                label = { Text(stringResource(R.string.telegram_chat_id_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = viewModel::startMatch,
            enabled = state.player1Name.isNotBlank() && state.player2Name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.start_match),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        OutlinedButton(
            onClick = viewModel::openHistory,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
