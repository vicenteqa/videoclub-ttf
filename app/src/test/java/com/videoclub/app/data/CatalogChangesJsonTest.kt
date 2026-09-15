package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shapes below are what `catalogo-maestro.py` publishes, trimmed. Ids arrive as numbers or as
 * strings depending on the supplier's mood, exactly as they do in the listings themselves.
 */
class CatalogChangesJsonTest {

    @Test
    fun `reads the index`() {
        val index = CatalogChangesJson.index("""{"version": 42, "generated_at": 1789462751, "oldest": 30}""")

        assertThat(index).isEqualTo(CatalogIndex(version = 42, oldest = 30))
    }

    @Test
    fun `an index without a version is no index`() {
        assertThat(CatalogChangesJson.index("""{"generated_at": 1}""")).isNull()
        assertThat(CatalogChangesJson.index("<html>502</html>")).isNull()
    }

    @Test
    fun `catching up needs every changes file after the local version`() {
        val index = CatalogIndex(version = 42, oldest = 30)

        assertThat(index.canCatchUpFrom(29)).isTrue()
        assertThat(index.canCatchUpFrom(41)).isTrue()
        assertThat(index.canCatchUpFrom(28)).isFalse()
        assertThat(index.canCatchUpFrom(42)).isFalse()
        assertThat(index.canCatchUpFrom(0)).isFalse()
    }

    @Test
    fun `the first versioned mirror has no changes to catch up with`() {
        assertThat(CatalogIndex(version = 1, oldest = 2).canCatchUpFrom(0)).isFalse()
    }

    @Test
    fun `reads a changes file`() {
        val changes = CatalogChangesJson.changes(
            """
            {"version": 42, "previous": 41, "generated_at": 1789462751,
             "categories": {"series": [{"category_id": "9", "category_name": "DRAMA HD", "parent_id": 0}]},
             "listings": [
               {"kind": "vod", "category_id": "413", "order": [4258835, "4258836", 7],
                "items": [{"num": 3, "name": "4K - Gladiator (2000)", "stream_id": "4258836",
                           "stream_icon": "https://image.tmdb.org/t/p/w600/g.jpg", "rating": "8.2",
                           "added": "1712345678", "container_extension": "mkv"}]},
               {"kind": "series", "category_id": 9, "order": [], "items": []}
             ],
             "removed_listings": [{"kind": "vod", "category_id": "99"}]}
            """
        )!!

        assertThat(changes.version).isEqualTo(42)
        assertThat(changes.previous).isEqualTo(41)
        assertThat(changes.categories.keys).containsExactly(Kind.Series)
        assertThat(changes.categories.getValue(Kind.Series)).containsExactly(RemoteCategory("9", "DRAMA HD"))

        val films = changes.listings.first()
        assertThat(films.kind).isEqualTo(Kind.Movie)
        assertThat(films.categoryId).isEqualTo("413")
        assertThat(films.order).containsExactly(4258835, 4258836, 7).inOrder()
        assertThat(films.items.single().remoteId).isEqualTo(4258836)
        assertThat(films.items.single().container).isEqualTo("mkv")

        assertThat(changes.listings[1].categoryId).isEqualTo("9")
        assertThat(changes.removedListings).containsExactly(Kind.Movie to "99")
    }

    @Test
    fun `a row the full download would skip leaves the order too`() {
        val changes = CatalogChangesJson.changes(
            """
            {"version": 2, "previous": 1, "categories": {},
             "listings": [{"kind": "vod", "category_id": "1", "order": [10, 11],
                           "items": [{"stream_id": 11, "name": "  "}]}],
             "removed_listings": []}
            """
        )!!

        assertThat(changes.listings.single().order).containsExactly(10)
        assertThat(changes.listings.single().items).isEmpty()
    }

    @Test
    fun `a changes file that is not whole is refused`() {
        assertThat(
            CatalogChangesJson.changes(
                """{"version": 2, "previous": 1, "listings": [{"kind": "vod", "category_id": "1"}]}"""
            )
        ).isNull()
        assertThat(
            CatalogChangesJson.changes("""{"version": 5, "previous": 3, "listings": []}""")
        ).isNull()
        assertThat(CatalogChangesJson.changes("not json")).isNull()
    }

    @Test
    fun `nothing changed reads as nothing to do`() {
        val changes = CatalogChangesJson.changes(
            """{"version": 3, "previous": 2, "categories": {}, "listings": [], "removed_listings": []}"""
        )!!

        assertThat(changes.categories).isEmpty()
        assertThat(changes.listings).isEmpty()
        assertThat(changes.removedListings).isEmpty()
    }
}
