package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `panelAccess` follows the document's usual contract, and the panel's address comes from `/informe`. */
class PanelAccessTest {

    private val report = """"reportUrl": "https://vps.example.org/informe""""

    @Test
    fun `absent is left alone, true and false are read as such`() {
        assertThat(ProviderOverrides.parse("""{}""")!!.panelAccess).isNull()
        assertThat(ProviderOverrides.parse("""{"panelAccess": true}""")!!.panelAccess).isTrue()
        assertThat(ProviderOverrides.parse("""{"panelAccess": false}""")!!.panelAccess).isFalse()
    }

    @Test
    fun `a household allowed to has the panel beside its report address`() {
        val config = ProviderConfig.empty().mergedWith(ProviderOverrides.parse("""{$report, "panelAccess": true}""")!!)

        assertThat(config.panelUrl).isEqualTo("https://vps.example.org/panel/")
    }

    @Test
    fun `a household not allowed to has no panel at all`() {
        val config = ProviderConfig.empty().mergedWith(ProviderOverrides.parse("""{$report}""")!!)

        assertThat(config.panelUrl).isEmpty()
    }

    @Test
    fun `it survives the cache`() {
        val cached = ProviderOverrides.parse(ProviderOverrides.parse("""{"panelAccess": true}""")!!.encode())

        assertThat(cached!!.panelAccess).isTrue()
    }
}
