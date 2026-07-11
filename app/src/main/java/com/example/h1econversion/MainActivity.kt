package com.example.h1econversion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.h1econversion.ui.screen.StartScreen
import com.example.h1econversion.ui.theme.H1eConversionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            H1eConversionTheme {
                StartScreen(
                    onConnectDevice = {
                        // TODO: デバイス接続画面へ遷移
                    },
                    onImportFile = {
                        // TODO: ファイルインポート画面へ遷移
                    },
                )
            }
        }
    }
}
