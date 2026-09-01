package com.videoclub.app.data

/**
 * One row of a browsing tab, before its posters have been read.
 *
 * [categoryIds] is a list rather than an id because a row is a genre and a genre is many of the
 * supplier's categories at once — see [RowPlan].
 */
data class RowSpec(val heading: String, val categoryIds: List<Long>)

/**
 * Turns the supplier's 625 film categories into a page somebody would want to scroll.
 *
 * The supplier's own list is not a page. Two hundred of its categories are one actor each, sixty are
 * football seasons, thirty are release years, twenty are streaming services, and the genres that are
 * in there exist three times over — `TERROR 4K`, `TERROR HD`, `TERROR HD60FPS` are one shelf split by
 * encode, which is a fact about the file and not about the film. Taken as it comes, it shows La Liga
 * where the horror shelf should be.
 *
 * So the page is genres, and nothing else: [Genres] reads a genre out of each category name, every
 * category that names the same genre is folded into one row, and everything that names no genre —
 * the actors, the leagues, the years, the platforms — simply does not become a row. What is lost
 * that way is reachable from the search box; what is gained is a page with eighteen shelves on it
 * instead of six hundred.
 */
object RowPlan {

    /**
     * One row per genre the supplier has anything in, in [Genre] order.
     *
     * A category that belongs to two genres is listed in both rows on purpose: `SCI-FI & FANTASIA`
     * is one category and two shelves, and a viewer looking for fantasy should not have to know that
     * the supplier bundled it with science fiction.
     */
    fun rows(categories: List<Category>): List<RowSpec> {
        val byGenre = LinkedHashMap<Genre, MutableList<Long>>()
        // In the supplier's own order, so that within a row the encode it leads with and the
        // ordering it chose both survive the merge.
        categories.sortedBy { it.position }.forEach { category ->
            Genres.of(category.name).forEach { genre ->
                byGenre.getOrPut(genre) { mutableListOf() }.add(category.id)
            }
        }
        return Genre.entries.mapNotNull { genre ->
            byGenre[genre]?.let { RowSpec(genre.label, it) }
        }
    }
}
