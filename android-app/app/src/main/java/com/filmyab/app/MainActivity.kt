package com.filmyab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.filmyab.app.ui.FilmYabApp
import com.filmyab.app.ui.theme.FilmYabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmYabTheme {
                FilmYabApp()
            }
        }
    }
}
