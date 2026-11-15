package tech.mmarca.openvitals.bridge.vertmatch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.bridge.BridgeApp
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsGroup

/**
 * Connect, disconnect, the training summary and the workouts to send. Also the redirect target
 * of the sign-in: the browser comes back to `<applicationId>://vertmatch/callback`, which this
 * activity (singleTask) receives, in [onCreate] or [onNewIntent].
 */
class VertMatchActivity : ComponentActivity() {
    private val connection: VertMatchConnection get() = (application as BridgeApp).vertMatch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: VertMatchViewModel = viewModel()
                    val connectionState by connection.state.collectAsStateWithLifecycle()
                    val workouts by viewModel.state.collectAsStateWithLifecycle()
                    // Android's own dialog, one per group. Whatever the answer, read the grants again.
                    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        viewModel.refresh()
                    }
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
                    LaunchedEffect(connectionState.status) {
                        if (connectionState.status == VertMatchStatus.Connected) connection.loadSummary()
                    }
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        VertMatchScreen(
                            connection = connectionState,
                            workouts = workouts,
                            canReadSummary = connection.canReadSummary,
                            actions = VertMatchActions(
                                onBack = ::finish,
                                onConnect = ::startSignIn,
                                onDisconnect = { lifecycleScope.launch { connection.disconnect() } },
                                onReloadSummary = { lifecycleScope.launch { connection.loadSummary() } },
                                onSelectBuild = viewModel::selectBuild,
                                onAskWorkouts = { ask.launch(viewModel.permissions(OpenVitalsGroup.WORKOUTS)) },
                                onAskRoutes = { ask.launch(viewModel.permissions(OpenVitalsGroup.ROUTES)) },
                                onSend = { viewModel.send(it) },
                                onSendAnyway = { viewModel.send(it, confirmed = true) },
                                onCancelSend = viewModel::cancelSend,
                                onRemove = viewModel::remove,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data
        if (!connection.isRedirect(uri)) return
        // Consumed once: a configuration change must not exchange the same code again.
        intent?.data = null
        lifecycleScope.launch { connection.completeSignIn(checkNotNull(uri)) }
    }

    private fun startSignIn() {
        lifecycleScope.launch {
            val signIn = connection.signInIntent()
            try {
                startActivity(signIn)
            } catch (_: ActivityNotFoundException) {
                // No browser on the phone: nothing to sign in with. The screen keeps saying disconnected.
            }
        }
    }
}
