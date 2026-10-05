package com.rangandale.appthrottler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.rangandale.appthrottler.ui.navigation.AppThrottlerApp
import com.rangandale.appthrottler.ui.theme.AppThrotlerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AppThrotlerTheme {
                AppThrottlerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
