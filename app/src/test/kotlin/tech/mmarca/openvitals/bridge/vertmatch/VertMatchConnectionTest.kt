package tech.mmarca.openvitals.bridge.vertmatch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VertMatchConnectionTest {
    @Test
    fun `the external id is the Health Connect session id behind a prefix`() {
        assertThat(VertMatchConnection.externalIdFor("3f2a-91")).isEqualTo("openvitals-3f2a-91")
    }

    @Test
    fun `the same session always gives the same external id`() {
        assertThat(VertMatchConnection.externalIdFor("abc")).isEqualTo(VertMatchConnection.externalIdFor("abc"))
    }

    @Test
    fun `an id VertMatch would refuse gives none`() {
        assertThat(VertMatchConnection.externalIdFor("")).isNull()
        assertThat(VertMatchConnection.externalIdFor("  ")).isNull()
        assertThat(VertMatchConnection.externalIdFor("has space")).isNull()
        assertThat(VertMatchConnection.externalIdFor("läuft")).isNull()
        assertThat(VertMatchConnection.externalIdFor("x".repeat(118))).isNull()
        assertThat(VertMatchConnection.externalIdFor("x".repeat(117))).isNotNull()
    }

    @Test
    fun `each build has its own redirect`() {
        assertThat(VertMatchConnection.redirectUriFor("tech.mmarca.openvitals.bridge"))
            .isEqualTo("tech.mmarca.openvitals.bridge://vertmatch/callback")
        assertThat(VertMatchConnection.redirectUriFor("tech.mmarca.openvitals.bridge.debug"))
            .isEqualTo("tech.mmarca.openvitals.bridge.debug://vertmatch/callback")
    }
}
