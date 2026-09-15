package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The panel's answers to `POST /videoclub/login`, as it gives them. */
class HouseholdLoginTest {

    @Test
    fun `a household's document url is a success`() {
        val outcome = LoginOutcome.fromResponse(
            200,
            """{"url": "https://vps.example.org/videoclub/abc/provider.json", "house": "Manel"}"""
        )

        assertThat(outcome).isEqualTo(
            LoginOutcome.Success("https://vps.example.org/videoclub/abc/provider.json", "Manel")
        )
    }

    @Test
    fun `a document url that is not https is not taken`() {
        assertThat(LoginOutcome.fromResponse(200, """{"url": "http://vps/provider.json", "house": "X"}"""))
            .isEqualTo(LoginOutcome.Unreachable)
    }

    @Test
    fun `a 200 that is not the panel's json is a captive portal, not a household`() {
        assertThat(LoginOutcome.fromResponse(200, "<html>Accept the terms</html>"))
            .isEqualTo(LoginOutcome.Unreachable)
    }

    @Test
    fun `401 is a refusal`() {
        assertThat(LoginOutcome.fromResponse(401, """{"error": "unauthorized"}"""))
            .isEqualTo(LoginOutcome.Refused)
    }

    @Test
    fun `429 says how long to wait`() {
        assertThat(LoginOutcome.fromResponse(429, """{"error": "too_many_attempts", "retry_after": 812}"""))
            .isEqualTo(LoginOutcome.TooManyAttempts(812))
        assertThat(LoginOutcome.fromResponse(429, ""))
            .isEqualTo(LoginOutcome.TooManyAttempts(0))
    }

    @Test
    fun `anything else is unreachable`() {
        assertThat(LoginOutcome.fromResponse(404, "")).isEqualTo(LoginOutcome.Unreachable)
        assertThat(LoginOutcome.fromResponse(502, "Bad Gateway")).isEqualTo(LoginOutcome.Unreachable)
    }
}
