package ch.sommermeyer.rubato

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ch.sommermeyer.rubato.ui.RubatoRoot
import ch.sommermeyer.rubato.ui.RubatoViewModel
import ch.sommermeyer.rubato.ui.theme.RubatoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RubatoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RubatoTheme {
                RubatoRoot(viewModel)
            }
        }
        if (savedInstanceState == null) {
            handleStartRequest(intent)
            viewModel.checkForUpdatesOnAppStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    /** "Tap to start" notifications open the activity, which may start the microphone-type service. */
    private fun handleStartRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_SERVICE, false) == true) {
            intent.removeExtra(EXTRA_START_SERVICE)
            viewModel.setServiceRunning(true)
        }
    }

    companion object {
        const val EXTRA_START_SERVICE = "ch.sommermeyer.rubato.extra.START_SERVICE"
    }
}
