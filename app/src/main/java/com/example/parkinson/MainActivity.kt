package com.example.parkinson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.parkinson.navigation.ParkinsonNavGraph
import com.example.parkinson.ui.theme.ParkinsonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: the app draws behind the status and navigation bars. The insets are applied once,
        // below, for every screen; see the Surface content.
        enableEdgeToEdge()
        setContent {
            ParkinsonTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // The Surface colour still fills the bar areas; only the content is kept clear of the
                    // status bar, the navigation bar (gesture or three-button), display cutouts and the IME.
                    // Screens must not add their own system-bar padding, or it would be counted twice.
                    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        val navController = rememberNavController()
                        ParkinsonNavGraph(navController = navController)
                    }
                }
            }
        }
    }
}
