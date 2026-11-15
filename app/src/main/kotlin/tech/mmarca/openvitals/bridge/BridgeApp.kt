package tech.mmarca.openvitals.bridge

import android.app.Application
import tech.mmarca.openvitals.bridge.openvitals.OpenVitalsReader
import tech.mmarca.openvitals.bridge.vertmatch.VertMatchConnection
import tech.mmarca.openvitals.bridge.vertmatch.VertMatchVault
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchConfig
import tech.mmarca.openvitals.bridge.vertmatch.api.VertMatchTokenClient

/** Holds what must exist once per process: the reader and each service's connection. */
class BridgeApp : Application() {
    val reader: OpenVitalsReader by lazy { OpenVitalsReader(this) }

    /** Built on first use. Its constructor reads neither the disk nor the Keystore. */
    internal val vertMatch: VertMatchConnection by lazy {
        val clientId = BuildConfig.VERTMATCH_CLIENT_ID
        val config = VertMatchConfig(
            clientId = clientId,
            redirectUri = if (clientId.isBlank()) "" else VertMatchConnection.redirectUriFor(packageName),
            requestRefreshToken = BuildConfig.VERTMATCH_REFRESH_TOKENS,
        )
        VertMatchConnection(config, VertMatchVault(this), VertMatchTokenClient(config), reader)
    }
}
