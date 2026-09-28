package io.github.brunovinicioslg.alumia.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.brunovinicioslg.alumia.ui.theme.AlumiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AlumiaTheme {
                MainRoute()
            }
        }
    }
}
