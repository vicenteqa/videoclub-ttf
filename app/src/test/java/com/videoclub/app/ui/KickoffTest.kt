package com.videoclub.app.ui

import com.google.common.truth.Truth.assertThat
import com.videoclub.app.data.FootballMatch
import org.junit.Test

class KickoffTest {

    private fun match(date: String, time: String?) = FootballMatch("A", "B", date, time, listOf(1))

    @Test
    fun `weekday, day, month and time, in Spanish`() {
        assertThat(kickoff(match("2026-09-20", "21:00"))).isEqualTo("dom 20 sep · 21:00")
    }

    @Test
    fun `no time, no dot`() {
        assertThat(kickoff(match("2026-08-15", null))).isEqualTo("sáb 15 ago")
    }
}
