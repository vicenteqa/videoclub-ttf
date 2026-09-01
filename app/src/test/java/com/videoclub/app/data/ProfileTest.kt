package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules of the household, which are edited on a television with a remote and therefore have to
 * be impossible to get wrong rather than merely documented.
 */
class ProfileTest {

    @Test
    fun `one letter is enough until it is not`() {
        val people = Profile.withInitials(
            listOf(Profile(0, "Vicente"), Profile(1, "Laura"), Profile(2, "Emma"))
        )

        assertThat(people.map { it.initial }).containsExactly("V", "L", "E").inOrder()
    }

    @Test
    fun `the prefix stops at what fits in the circle`() {
        // `Vicente` and `Vicenta` only differ at the seventh letter, and seven letters do not fit in
        // a 44dp circle. Two of the same beats one unreadable one.
        val people = Profile.withInitials(listOf(Profile(0, "Vicente"), Profile(1, "Vicenta")))

        assertThat(people.map { it.initial }).containsExactly("VIC", "VIC").inOrder()
    }

    @Test
    fun `two letters where two letters do`() {
        val people = Profile.withInitials(listOf(Profile(0, "Emma"), Profile(1, "Eva")))

        assertThat(people.map { it.initial }).containsExactly("EM", "EV").inOrder()
    }

    @Test
    fun `a household needs somebody in it`() {
        assertThat(Profile.problemWith(emptyList())).isEqualTo(Profile.Problem.NobodyLeft)
    }

    @Test
    fun `a nameless profile has nothing to draw`() {
        assertThat(Profile.problemWith(listOf(Profile(0, "  "))))
            .isEqualTo(Profile.Problem.NoName)
    }

    @Test
    fun `two of the same name is a coin toss`() {
        val problem = Profile.problemWith(listOf(Profile(0, "Emma"), Profile(1, "emma ")))

        assertThat(problem).isEqualTo(Profile.Problem.SameName)
    }

    @Test
    fun `three is the limit`() {
        val four = (0..3).map { Profile(it, "Persona $it") }

        assertThat(Profile.problemWith(four)).isEqualTo(Profile.Problem.TooMany)
        assertThat(Profile.problemWith(four.dropLast(1))).isNull()
    }

    @Test
    fun `a deleted number is not handed straight back out`() {
        val afterDeletingEmma = listOf(Profile(0, "Vicente"), Profile(1, "Laura"), Profile(3, "Ana"))

        assertThat(Profile.nextId(afterDeletingEmma)).isEqualTo(4)
    }
}
