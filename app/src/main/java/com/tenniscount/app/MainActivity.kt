package com.tenniscount.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tenniscount.app.ui.MatchViewModel
import com.tenniscount.app.ui.Screen
import com.tenniscount.app.ui.scoreboard.ScoreboardScreen
import com.tenniscount.app.ui.setup.MatchSetupScreen
import com.tenniscount.app.ui.theme.TennisCountTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TennisCountTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: MatchViewModel = viewModel()
                    val state by viewModel.uiState.collectAsState()
                    when (state.screen) {
                        Screen.SETUP -> MatchSetupScreen(state, viewModel)
                        Screen.SCOREBOARD -> ScoreboardScreen(state, viewModel)
                    }
                }
            }
        }
    }
}
