package tech.mmarca.openvitals.bridge.vertmatch

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.bridge.openvitals.FileResult
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsApi
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsBuild
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsGroup
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsPaths
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsReader
import tech.mmarca.openvitals.bridge.openvitals.ReadResult
import tech.mmarca.openvitals.bridge.vertmatch.api.AuthorizationResult
import tech.mmarca.openvitals.bridge.vertmatch.api.ErrorAction
import tech.mmarca.openvitals.bridge.vertmatch.api.SummaryZone
import tech.mmarca.openvitals.bridge.vertmatch.api.UploadOutcome
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchApi
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchAuth
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchConfig
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchError
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchResult
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchScope
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchSummary
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchTokenClient

internal enum class VertMatchStatus {
    /** This build carries no client id: VertMatch has not approved it, so nothing can connect. */
    NotConfigured,
    Disconnected,
    Connected,

    /** VertMatch switched this app off (`client_not_allowed`, `app_suspended`): no calls at all. */
    AppStopped,
}

internal data class VertMatchUiState(
    val status: VertMatchStatus,
    val busy: Boolean = false,
    val summary: VertMatchSummary? = null,
    /** External ids of the workouts VertMatch holds a copy of. */
    val sent: Set<String> = emptySet(),
    /** Deleted on VertMatch by the runner: VertMatch keeps them deleted, so the app offers no Send. */
    val known: Set<String> = emptySet(),
    val message: String? = null,
)

/**
 * The runner's connection to VertMatch: sign-in, sending and deleting workouts, and the training
 * summary. One per process. Calls are serialised, so a token refresh never races another call.
 * Nothing here logs: no token, no file and no id leaves through the log.
 *
 * The vault reads the disk and the Keystore, so it is only touched on [Dispatchers.IO]: the
 * constructor reads nothing, and the stored connection loads in the background, with the state
 * busy until it has.
 */
internal class VertMatchConnection(
    private val config: VertMatchConfig,
    private val vault: VertMatchVault,
    private val tokenClient: VertMatchTokenClient,
    private val reader: OpenVitalsReader,
    private val api: VertMatchApi = VertMatchApi(config, { vault.tokens?.accessToken }),
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        VertMatchUiState(
            status = if (config.isConfigured) VertMatchStatus.Disconnected else VertMatchStatus.NotConfigured,
            busy = config.isConfigured,
        ),
    )
    val state: StateFlow<VertMatchUiState> = _state.asStateFlow()

    init {
        // serial publishes what the vault holds when it ends.
        if (config.isConfigured) scope.launch { serial {} }
    }

    val canReadSummary: Boolean get() = VertMatchScope.SummaryRead in config.scopes

    /** The browser intent that starts sign-in. The verifier waits, encrypted, for the redirect. */
    suspend fun signInIntent(): Intent = withContext(Dispatchers.IO) {
        val request = VertMatchAuth.begin(config)
        vault.pendingSignIn = PendingSignIn(request.state, request.verifier)
        Intent(Intent.ACTION_VIEW, Uri.parse(request.url))
    }

    /** True when [uri] is this app's VertMatch redirect, which [completeSignIn] then handles. */
    fun isRedirect(uri: Uri?): Boolean =
        uri != null && config.isConfigured && uri.toString().startsWith(config.redirectUri)

    suspend fun completeSignIn(redirect: Uri) = serial {
        val pending = vault.pendingSignIn ?: return@serial
        when (val result = VertMatchAuth.parseRedirect(redirect.toString(), pending.state)) {
            is AuthorizationResult.Code -> {
                vault.pendingSignIn = null
                when (val exchanged = tokenClient.exchangeCode(result.code, pending.verifier)) {
                    is VertMatchResult.Success -> {
                        val tokens = exchanged.value
                        vault.tokens = StoredTokens(tokens.accessToken, tokens.refreshToken, expiry(tokens.expiresInSeconds))
                        vault.appStopped = false
                        publish(Messages.CONNECTED)
                    }
                    is VertMatchResult.Failure -> publish(messageFor(exchanged.error))
                }
            }
            is AuthorizationResult.Denied -> {
                vault.pendingSignIn = null
                publish(Messages.DECLINED)
            }
            // Not the redirect of the sign-in this app started: ignore it.
            AuthorizationResult.Invalid -> Unit
        }
    }

    /** Forgets the connection on this phone: tokens, the Keystore key and what was sent. */
    suspend fun disconnect() = serial {
        vault.clear()
        _state.update { it.copy(summary = null) }
        publish(Messages.DISCONNECTED)
    }

    /**
     * Sends the workout [sessionId] of [build] as the FIT file OpenVitals writes for it, with its
     * route when the bridge may read routes. Sending the same workout again is safe: its external
     * id is derived from the Health Connect session id, so VertMatch answers `unchanged`.
     */
    suspend fun send(build: OpenVitalsBuild, sessionId: String) = serial {
        val externalId = externalIdFor(sessionId) ?: return@serial publish(Messages.CANNOT_SEND)
        val withRoute = reader.isGranted(build, OpenVitalsGroup.ROUTES)
        val fit = when (val file = reader.readFile(build, OpenVitalsPaths.workoutFit(sessionId, withRoute))) {
            is FileResult.Read -> file.bytes
            FileResult.Denied -> return@serial publish(Messages.NOT_ALLOWED)
            is FileResult.Failed -> return@serial publish(messageForOpenVitals(file.status))
            FileResult.Unavailable -> return@serial publish(Messages.OPENVITALS_TOO_OLD)
        }
        when (val result = call { api.uploadActivity(externalId, fit) }) {
            is VertMatchResult.Success -> when (result.value) {
                is UploadOutcome.Created, is UploadOutcome.Unchanged -> {
                    vault.markSent(externalId)
                    publish(Messages.SENT)
                }
                // The runner deleted it in VertMatch; it stays deleted and is not sent again.
                is UploadOutcome.Known -> {
                    vault.markKnown(externalId)
                    publish(Messages.KNOWN)
                }
                is UploadOutcome.Duplicate -> publish(Messages.DUPLICATE)
            }
            is VertMatchResult.Failure -> publish(messageFor(result.error))
        }
        flushPendingDeletes()
    }

    /** Deletes VertMatch's copy of a workout the runner sent, and keeps the workout in OpenVitals. */
    suspend fun remove(sessionId: String) = serial {
        val externalId = externalIdFor(sessionId) ?: return@serial
        if (externalId !in vault.sent) return@serial
        vault.forgetSent(externalId)
        vault.addPendingDelete(externalId)
        flushPendingDeletes()
        publish(if (externalId in vault.pendingDeletes) Messages.REMOVE_LATER else Messages.REMOVED)
    }

    /**
     * Finds the sent workouts the runner has since deleted in OpenVitals, and deletes VertMatch's
     * copies. OpenVitals tells nobody about a delete, so the bridge asks for each sent workout:
     * only a clean answer with no rows means it is gone. Sharing off, a missing grant or a failed
     * read says nothing about the workout, and leaves it alone.
     */
    suspend fun reconcileDeletes(build: OpenVitalsBuild) = serial {
        if (vault.tokens == null) return@serial
        for (externalId in vault.sent + vault.known) {
            val sessionId = externalId.removePrefix(EXTERNAL_ID_PREFIX)
            if (!isDeletedInOpenVitals(build, sessionId)) continue
            vault.forgetKnown(externalId)
            if (externalId in vault.sent) {
                vault.forgetSent(externalId)
                vault.addPendingDelete(externalId)
            }
        }
        flushPendingDeletes()
    }

    suspend fun loadSummary() = serial {
        if (!canReadSummary) return@serial
        when (val result = call { api.summary(SummaryZone.systemDefault()) }) {
            is VertMatchResult.Success -> _state.update { it.copy(summary = result.value) }
            is VertMatchResult.Failure -> publish(messageFor(result.error))
        }
        flushPendingDeletes()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun isDeletedInOpenVitals(build: OpenVitalsBuild, sessionId: String): Boolean {
        val answer = reader.read(build, OpenVitalsPaths.session(OpenVitalsGroup.WORKOUTS, sessionId))
        val table = (answer as? ReadResult.Read)?.table ?: return false
        return table.status == OpenVitalsApi.STATUS_OK && table.rows.isEmpty()
    }

    private fun refresh() = _state.update { it.fromVault() }

    /** Deletes still owed to VertMatch. A transient failure leaves them for the next call. */
    private suspend fun flushPendingDeletes() {
        for (externalId in vault.pendingDeletes) {
            when (val result = call { api.deleteActivity(externalId) }) {
                is VertMatchResult.Success -> vault.removePendingDelete(externalId)
                is VertMatchResult.Failure -> when (result.error.action) {
                    ErrorAction.RetryWithBackoff, ErrorAction.RefreshOrReauthenticate,
                    ErrorAction.ReauthenticateOnce, ErrorAction.PauseRunner,
                    -> return
                    // The copy cannot be reached any more (account gone, app stopped, not allowed).
                    else -> vault.removePendingDelete(externalId)
                }
            }
        }
    }

    /**
     * One API call with the document's handling around it: a fresh token first, one refresh and
     * retry on `TOKEN_EXPIRED`, and the connection state changed for the codes that change it.
     */
    private suspend fun <T> call(block: () -> VertMatchResult<T>): VertMatchResult<T> = withContext(Dispatchers.IO) {
        if (vault.appStopped) return@withContext stoppedFailure()
        if (vault.tokens == null) return@withContext VertMatchResult.Failure(VertMatchError("token_missing", 0, "not connected"))
        if (isExpired(vault.tokens)) refreshTokens()
        var result = block()
        if (result is VertMatchResult.Failure && result.error.action == ErrorAction.RefreshOrReauthenticate && refreshTokens()) {
            result = block()
        }
        if (result is VertMatchResult.Failure) apply(result.error.action)
        result
    }

    private fun apply(action: ErrorAction) {
        when (action) {
            // Expired and the refresh did not help. A refused refresh token already ended the session
            // (refreshTokens); one that only failed to reach Auth0 is kept for the next try.
            ErrorAction.RefreshOrReauthenticate -> if (vault.tokens?.refreshToken == null) vault.tokens = null
            ErrorAction.ReauthenticateOnce, ErrorAction.NoRetryReconnect -> vault.tokens = null
            ErrorAction.DropConnection -> vault.clear()
            ErrorAction.StopAllCalls -> vault.appStopped = true
            ErrorAction.PauseRunner, ErrorAction.RetryWithBackoff, ErrorAction.FixInput -> Unit
        }
        refresh()
    }

    /** True when the access token was renewed. A refused refresh token ends the session. */
    private fun refreshTokens(): Boolean {
        val stored = vault.tokens ?: return false
        val refreshToken = stored.refreshToken ?: return false
        return when (val result = tokenClient.refresh(refreshToken)) {
            is VertMatchResult.Success -> {
                val tokens = result.value
                vault.tokens = StoredTokens(tokens.accessToken, tokens.refreshToken, expiry(tokens.expiresInSeconds))
                true
            }
            is VertMatchResult.Failure -> {
                if (result.error.code == "invalid_grant") vault.tokens = null
                false
            }
        }
    }

    private fun isExpired(tokens: StoredTokens?): Boolean {
        val expiresAt = tokens?.expiresAtMillis ?: return false
        return System.currentTimeMillis() > expiresAt - EXPIRY_MARGIN_MILLIS
    }

    private fun expiry(expiresInSeconds: Long?): Long? = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 }

    private fun currentStatus(): VertMatchStatus = when {
        !config.isConfigured -> VertMatchStatus.NotConfigured
        vault.appStopped -> VertMatchStatus.AppStopped
        vault.tokens != null -> VertMatchStatus.Connected
        else -> VertMatchStatus.Disconnected
    }

    private fun publish(message: String) = _state.update { it.fromVault().copy(message = message) }

    /** What the vault says now. Called on IO only, like every other vault read. */
    private fun VertMatchUiState.fromVault() = copy(status = currentStatus(), sent = vault.sent, known = vault.known)

    /** One call at a time, on [Dispatchers.IO] wherever it was started: every step reads the vault. */
    private suspend fun serial(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        _state.update { it.copy(busy = true) }
        try {
            mutex.withLock { block() }
        } finally {
            _state.update { it.fromVault().copy(busy = false) }
        }
    }

    private fun stoppedFailure() = VertMatchResult.Failure(VertMatchError("app_suspended", 0, "switched off"))

    private fun messageFor(error: VertMatchError): String = when (error.action) {
        // Still connected after an expiry means the refresh only failed to get through.
        ErrorAction.RefreshOrReauthenticate -> if (vault.tokens != null) Messages.TRY_LATER else Messages.SIGN_IN_AGAIN
        ErrorAction.ReauthenticateOnce, ErrorAction.NoRetryReconnect -> Messages.SIGN_IN_AGAIN
        ErrorAction.DropConnection -> Messages.ACCOUNT_DELETED
        ErrorAction.PauseRunner -> Messages.ACCOUNT_SUSPENDED
        ErrorAction.StopAllCalls -> Messages.APP_STOPPED
        ErrorAction.RetryWithBackoff -> Messages.TRY_LATER
        ErrorAction.FixInput -> if (error.code == "not_load_eligible") Messages.NO_DURATION else Messages.REFUSED
    }

    /** Why OpenVitals gave no FIT file, in the words of the status it answered with. */
    private fun messageForOpenVitals(status: String?): String = when (status) {
        "sharing_off" -> Messages.SHARING_OFF
        "background_read_missing" -> Messages.BACKGROUND_READ_MISSING
        "rate_limited" -> Messages.OPENVITALS_BUSY
        OpenVitalsApi.STATUS_NOT_FOUND -> Messages.WORKOUT_GONE
        "file_too_large" -> Messages.TOO_LARGE
        else -> Messages.OPENVITALS_FAILED
    }

    internal object Messages {
        const val CONNECTED = "VertMatch is connected."
        const val DECLINED = "VertMatch was not connected."
        const val DISCONNECTED = "VertMatch is disconnected."
        const val SENT = "Sent to VertMatch."
        const val KNOWN = "You deleted this workout on VertMatch, so it is not sent again."
        const val DUPLICATE = "VertMatch already has this workout from another app."
        const val REMOVED = "Removed from VertMatch."
        const val REMOVE_LATER = "VertMatch could not be reached. The workout is removed there on the next try."
        const val CANNOT_SEND = "This workout cannot be sent."
        const val NO_DURATION = "VertMatch needs a workout with a duration."
        const val REFUSED = "VertMatch did not accept this workout."
        const val SIGN_IN_AGAIN = "Connect VertMatch again to continue."
        const val ACCOUNT_DELETED = "Your VertMatch account was deleted, so the connection was removed."
        const val ACCOUNT_SUSPENDED = "Your VertMatch account is suspended."
        const val APP_STOPPED = "VertMatch has switched off access for OpenBridge."
        const val TRY_LATER = "VertMatch could not be reached. Try again later."
        const val NOT_ALLOWED = "Allow OpenBridge to read workouts from OpenVitals first."
        const val SHARING_OFF = "Sharing is off in OpenVitals. Turn it on there first."
        const val BACKGROUND_READ_MISSING =
            "OpenVitals may not read Health Connect while it is closed. Allow background reads in its sharing settings."
        const val OPENVITALS_BUSY = "Health Connect is asking OpenVitals to slow down. Try again in a few minutes."
        const val WORKOUT_GONE = "OpenVitals no longer has this workout."
        const val TOO_LARGE = "This workout's file is larger than VertMatch accepts."
        const val OPENVITALS_FAILED = "OpenVitals could not write this workout's file. Try again."
        const val OPENVITALS_TOO_OLD = "This OpenVitals cannot share a workout as a file. Update OpenVitals."
    }

    companion object {
        private const val EXPIRY_MARGIN_MILLIS = 60_000L
        private const val EXTERNAL_ID_PREFIX = "openvitals-"

        /** The redirect VertMatch must have registered for this build: one per application id. */
        fun redirectUriFor(applicationId: String): String = "$applicationId://vertmatch/callback"

        /**
         * The stable id of a workout on VertMatch: its Health Connect session id, which never changes
         * for the same session, behind a prefix that says where it came from. Null for an id VertMatch
         * would refuse (it takes visible ASCII only).
         */
        fun externalIdFor(healthConnectId: String): String? =
            "$EXTERNAL_ID_PREFIX$healthConnectId".takeIf { id ->
                healthConnectId.isNotBlank() && id.length <= 128 && id.all { it.code in 0x21..0x7E }
            }
    }
}
