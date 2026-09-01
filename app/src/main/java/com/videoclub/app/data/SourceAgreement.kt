package com.videoclub.app.data

import kotlin.math.abs

/**
 * Which copies of a film are actually the same film.
 *
 * The supplier publishes a popular title two to four times, one row per encode, and the app folds
 * them into one [Title] on name and year alone — that is what [TitleNaming.mergeKey] can see, since
 * the catalogue listing carries nothing else. Almost always right, and occasionally not: the three
 * rows called `Ben-Hur (1959)` are the 4K of the 1959 film, the HD60 of the 1959 film, and a
 * two-hour film that is not it. Whoever filed it typed the wrong year. The quality picker then
 * offers `HD` as a third encode of what you were watching, and it starts a different picture.
 *
 * The one thing that gives it away is the running time, and the supplier is honest about that even
 * when the name lies — the odd copy of Ben-Hur declares its own 2 h 03 while the other two declare
 * 3 h 42. So: ask each copy how long it is and keep the ones that agree.
 *
 * ### Only a clear majority decides
 *
 * With three copies and one dissenter the answer is obvious. With two copies that disagree it is
 * not: nothing here can tell which of the two is the film you asked for, and dropping the wrong one
 * would hide a perfectly good encode to spare a mistake that at least announces itself. Ties are
 * therefore left exactly as they came, and a copy the supplier never probed is kept for the same
 * reason — silence is not evidence.
 */
object SourceAgreement {

    /**
     * How far two running times can differ and still be the same film.
     *
     * Wide on purpose. The same picture legitimately varies by a few minutes across encodes —
     * different credits, a frame rate conversion, a trim at the head — and the mistakes this exists
     * to catch are not subtle: they are a two-hour film shelved as a three-and-three-quarter-hour
     * one. Nothing observed in this catalogue falls in between.
     */
    const val TOLERANCE = 0.15

    /**
     * The copies worth offering, in the order they came.
     *
     * [durations] is every copy of one film with what it says its running time is, in seconds, or
     * null where the supplier probed nothing.
     */
    fun agreeing(durations: List<Pair<Source, Int?>>): List<Source> {
        val known = durations.mapNotNull { (source, seconds) ->
            seconds?.takeIf { it > 0 }?.let { source to it }
        }
        // Nothing to disagree with.
        if (known.size < 2) return durations.map { it.first }

        // One cluster per copy: everything within tolerance of *that* copy's running time. The
        // clusters overlap, which is the point — the film's own copies all agree with each other
        // and so name the same set, while the impostor names only itself.
        val clusters = known.map { (_, seconds) ->
            known.filter { (_, other) -> abs(other - seconds) <= TOLERANCE * seconds }
                .map { it.first }
                .toSet()
        }
        val biggest = clusters.maxOf { it.size }
        val winners = clusters.filter { it.size == biggest }.toSet()
        // Two different majorities and no way to choose between them.
        if (winners.size != 1) return durations.map { it.first }

        val agreed = winners.first()
        return durations.map { it.first }.filter { source ->
            source in agreed || known.none { it.first == source }
        }
    }
}
