package sh.eliza.pebble.calnotify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import sh.eliza.pebble.calnotify.ui.theme.PebbleKitSampleTheme

class MainActivity : ComponentActivity() {
    private val pebbleManager = PebbleManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PebbleKitSampleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }
}
