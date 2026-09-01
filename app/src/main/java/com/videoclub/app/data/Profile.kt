package com.videoclub.app.data

import androidx.compose.runtime.Immutable

/**
 * Who is watching.
 *
 * The account is one account and the catalogue is one catalogue — what a profile separates is the
 * two tables the viewer writes: where each of them got to in a series, and what each of them saved
 * to watch later. Everything else on screen is identical whoever is holding the remote, with one
 * exception: [childrenOnly].
 *
 * These used to be an enum, three names compiled into the APK. They are edited from the settings
 * screen now, and that changes one thing that matters: [id] is what the history tables store, so it
 * belongs to the person and not to their position in a list. A rename keeps the id and keeps the
 * history with it. A deletion takes the history with it too, which is what makes handing the number
 * to somebody new safe afterwards.
 *
 * Profiles are per-device. There is no server behind this app, so the phone and the television keep
 * separate people as well as separate histories, and they did before any of this was editable.
 *
 * @property childrenOnly whether this profile sees the whole shop or only the children's shelves.
 * It narrows every read of the catalogue — the rows, the genre chips, the search box — to what
 * [Genres.isForChildren] allows and [Genres.isForGrownUps] does not veto. It is not a lock and is
 * not meant to be one: it is for looking up a film with a child in the room without the shelves of
 * the other kind of film going past on the way.
 */
@Immutable
data class Profile(
    val id: Int,
    val name: String,
    val childrenOnly: Boolean = false,
    /**
     * What the chip in the tab strip draws: as much of the name as it takes to tell this person
     * from the others, which is nearly always one letter. Filled in by [withInitials] when the list
     * is read, because the answer is a property of the whole household and not of one person.
     */
    val initial: String = name.take(1).uppercase()
) {

    companion object {
        /** Enough for this house, and few enough that the chooser stays one row of faces. */
        const val MAX = 3

        /** As many letters as fit in the circle the chip draws. */
        private const val INITIAL_MAX = 3

        /**
         * A household the hosted document has not described yet: one person, no separation.
         *
         * It used to be three names compiled into the APK, which was fine while the same build ran
         * in one house. It is not fine now that the same build runs in somebody else's: an install
         * whose document says nothing about people must not invite the in-laws to watch as
         * «Vicente». One profile means the chooser never appears and every history lands in the
         * same place, which is exactly right for a household of one and harmless for one that has
         * not been set up yet.
         */
        val DEFAULT = listOf(Profile(id = 0, name = "Casa"))

        /**
         * The same list, each with the shortest opening of its name that no other name shares.
         *
         * `Vicente` and `Laura` are `V` and `L`; add `Eva` beside `Emma` and those two become `EV`
         * and `EM`, because one letter no longer says which is which. Two identical circles are the
         * one thing this chip must not draw, since the whole of its job is saying whose evening is
         * being recorded.
         *
         * It stops at [INITIAL_MAX] all the same. Beyond three letters the text is wider than the
         * circle it is drawn in and gets clipped into a smear, which is a worse answer than a
         * repeat — and it takes two people whose names match for three letters to get there.
         *
         * Accents count as different letters here on purpose: they are different letters.
         */
        fun withInitials(profiles: List<Profile>): List<Profile> = profiles.map { profile ->
            val name = profile.name.uppercase()
            val others = profiles.filterNot { it.id == profile.id }.map { it.name.uppercase() }
            val length = (1..minOf(name.length, INITIAL_MAX)).firstOrNull { n ->
                others.none { other -> other.take(n) == name.take(n) }
            } ?: INITIAL_MAX
            profile.copy(initial = name.take(length.coerceAtLeast(1)))
        }

        /**
         * What is wrong with an edited list of people, or null when nothing is.
         *
         * Here rather than in the screen so that the rules are one paragraph in one place and can be
         * tested without a device: a household needs somebody in it, two people with the same name
         * make the chip a coin toss, and a nameless profile has nothing to draw in it.
         */
        fun problemWith(profiles: List<Profile>): Problem? {
            val names = profiles.map { it.name.trim() }
            return when {
                profiles.isEmpty() -> Problem.NobodyLeft
                names.any { it.isEmpty() } -> Problem.NoName
                names.map(String::uppercase).distinct().size != names.size -> Problem.SameName
                profiles.size > MAX -> Problem.TooMany
                else -> null
            }
        }

        /**
         * A number nobody in [profiles] is using.
         *
         * One past the highest rather than the first gap: a gap is the id of somebody who was
         * deleted, and while their history went with them, handing their number straight back out
         * is the kind of thing that is fine until the day it is not.
         */
        fun nextId(profiles: List<Profile>): Int = (profiles.maxOfOrNull { it.id } ?: -1) + 1
    }

    /** The four ways an edited list of people can be one that nobody should be able to save. */
    enum class Problem { NobodyLeft, NoName, SameName, TooMany }
}
