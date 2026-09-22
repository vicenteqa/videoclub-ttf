package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Test

/** What the panel is told about live television, and when it is told nothing. */
class WatchReporterTest {

    private val sent = mutableListOf<JSONObject>()

    private val http = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            synchronized(sent) { sent += JSONObject(buffer.readUtf8()) }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        })
        .build()

    private val scope = CoroutineScope(SupervisorJob())

    private val reporter = WatchReporter(http, scope, nowMillis = { 1_000_000L }) {
        ProviderConfig.empty().copy(reportUrl = "https://panel.example/informe", reportToken = "t")
    }

    /** Waits for every request the reporter launched. */
    private fun settle(): List<JSONObject> = runBlocking {
        scope.coroutineContext[Job]!!.children.forEach { it.join() }
        synchronized(sent) { sent.toList() }
    }

    @Test
    fun `a settled channel says what its guide has on`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, "Telediario 2")

        val body = settle().single()
        assertThat(body.getString("canal")).isEqualTo("La 1")
        assertThat(body.getString("programa")).isEqualTo("Telediario 2")
    }

    @Test
    fun `no guide, no programme field`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, null)

        assertThat(settle().single().has("programa")).isFalse()
    }

    @Test
    fun `a new programme on the settled channel is reported again`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, "Telediario 2")
        reporter.programmeChanged("La 1", "Telediario 2")
        reporter.programmeChanged("La 1", "La Revuelta")

        assertThat(settle().map { it.getString("programa") })
            .containsExactly("Telediario 2", "La Revuelta").inOrder()
    }

    @Test
    fun `programmes of channels zapped past are not reported`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, "Telediario 2")
        reporter.programmeChanged("La 2", "Documental")

        assertThat(settle().map { it.getString("canal") }).containsExactly("La 1")
    }

    @Test
    fun `a guide that ran out says nothing`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, "Telediario 2")
        reporter.programmeChanged("La 1", null)

        assertThat(settle()).hasSize(1)
    }

    @Test
    fun `tuning is said at once and marked provisional`() {
        reporter.tuning("La 1", WatchReporter.Kind.Channel, "Telediario 2")

        val body = settle().single()
        assertThat(body.getBoolean("provisional")).isTrue()
        assertThat(body.getString("programa")).isEqualTo("Telediario 2")
    }

    @Test
    fun `tuning the same channel twice says it once`() {
        reporter.tuning("La 1", WatchReporter.Kind.Channel)
        reporter.tuning("La 1", WatchReporter.Kind.Channel)

        assertThat(settle()).hasSize(1)
    }

    @Test
    fun `the settled report is never marked provisional`() {
        reporter.tuning("La 1", WatchReporter.Kind.Channel)
        reporter.settledOn("La 1", WatchReporter.Kind.Channel)

        assertThat(settle().map { it.optBoolean("provisional") }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `what has already settled is not announced again as tuning`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel)
        reporter.tuning("La 1", WatchReporter.Kind.Channel)

        assertThat(settle()).hasSize(1)
    }

    @Test
    fun `nothing about programmes once playback stopped`() {
        reporter.settledOn("La 1", WatchReporter.Kind.Channel, "Telediario 2")
        reporter.stopped()
        reporter.programmeChanged("La 1", "La Revuelta")

        assertThat(settle().filter { it.has("programa") }.map { it.getString("programa") })
            .containsExactly("Telediario 2")
    }

    @Test
    fun `the profile on travels with what is on`() {
        var who: String? = "Vicente"
        val reporter = WatchReporter(http, scope, nowMillis = { 1_000_000L }, viewer = { who }) {
            ProviderConfig.empty().copy(reportUrl = "https://panel.example/informe", reportToken = "t")
        }
        reporter.settledOn("Drácula", WatchReporter.Kind.Film)
        who = "Marta"
        reporter.settledOn("Drácula", WatchReporter.Kind.Film)
        who = null
        reporter.settledOn("La 1", WatchReporter.Kind.Channel)

        // Each request is its own coroutine, so they arrive in any order.
        assertThat(settle().map { "${it.getString("canal")}:${it.optString("profile", "-")}" })
            .containsExactly("Drácula:Vicente", "Drácula:Marta", "La 1:-")
    }
}
