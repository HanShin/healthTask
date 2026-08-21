package com.hanshin.healthtask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.hanshin.healthtask.ui.HealthTaskRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF315D4D),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD8E9DF),
                    secondary = Color(0xFF91572F),
                    background = Color(0xFFFFFBF5),
                    surface = Color(0xFFFFFBF5),
                    surfaceVariant = Color(0xFFF3EDE4),
                )
            ) {
                HealthTaskRoot()
            }
        }
    }
}
