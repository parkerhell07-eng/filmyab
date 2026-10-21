package com.filmyab.app

import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.filmyab.app.ui.FilmYabApp
import com.filmyab.app.ui.theme.FilmYabTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmYabTheme {
                FilmYabApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Zero-input flow: read the clipboard (allowed while the activity is focused)
        // and let the smart engine run by itself when it holds an Instagram link.
        val clip = try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            null
        }
        viewModel.onAppResumed(clip)
    }
}
