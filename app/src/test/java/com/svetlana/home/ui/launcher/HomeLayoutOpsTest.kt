package com.svetlana.home.ui.launcher

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class HomeLayoutOpsTest {

    private fun apps(vararg p: String) = HomeLayout(items = p.map { HomeItem.App(it) }, initialized = true)

    @Test
    fun initialUsesFavoritesThenSuggestionsAndSkipsDock() {
        val fromFav = HomeLayoutOps.initial(listOf("tg", "dialer"), listOf("vk"), dock = setOf("dialer"))
        assertThat(fromFav.items).containsExactly(HomeItem.App("tg"))
        assertThat(fromFav.initialized).isTrue()
        val fromSug = HomeLayoutOps.initial(emptyList(), listOf("vk", "yt"), dock = emptySet())
        assertThat(fromSug.pinnedPackages).containsExactly("vk", "yt")
    }

    @Test
    fun unpinningLastAppLeavesEmptyHomeInsteadOfSuggestions() {
        val l = HomeLayoutOps.unpin(apps("tg"), "tg")
        assertThat(l.items).isEmpty()
        assertThat(l.initialized).isTrue()
    }

    @Test
    fun pinIsIdempotentAndAppends() {
        val l = HomeLayoutOps.pin(HomeLayoutOps.pin(apps("a"), "b"), "b")
        assertThat(l.items).containsExactly(HomeItem.App("a"), HomeItem.App("b")).inOrder()
    }

    @Test
    fun moveReorders() {
        val l = HomeLayoutOps.move(apps("a", "b", "c"), from = 0, to = 2)
        assertThat(l.items.map { (it as HomeItem.App).pkg }).containsExactly("b", "c", "a").inOrder()
        assertThat(HomeLayoutOps.move(apps("a"), 5, 0)).isEqualTo(apps("a"))
    }

    @Test
    fun dropAppOntoAppCreatesFolderAndOntoFolderAdds() {
        val folder = HomeLayoutOps.dropOnto(apps("a", "b", "c"), from = 2, target = 0, newFolderId = "f1")
        assertThat(folder.items).hasSize(2)
        val f = folder.items[0] as HomeItem.Folder
        assertThat(f.apps).containsExactly("a", "c").inOrder()
        val more = HomeLayoutOps.dropOnto(folder, from = 1, target = 0, newFolderId = "x")
        assertThat((more.items.single() as HomeItem.Folder).apps).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun removingFromFolderOfTwoDissolvesItAndKeepsAppOnHome() {
        val l = HomeLayout(items = listOf(HomeItem.Folder("f", "Папка", listOf("a", "b"))), initialized = true)
        val r = HomeLayoutOps.removeFromFolder(l, "f", "a")
        assertThat(r.items).containsExactly(HomeItem.App("b"), HomeItem.App("a")).inOrder()
    }

    @Test
    fun addToFolderFromMenu() {
        val created = HomeLayoutOps.addToFolder(apps("a", "b"), "b", folderId = null, newFolderId = "f")
        assertThat(created.items[1]).isEqualTo(HomeItem.Folder("f", "Папка", listOf("b")))
        val added = HomeLayoutOps.addToFolder(created, "a", folderId = "f", newFolderId = "z")
        assertThat(added.items.single()).isEqualTo(HomeItem.Folder("f", "Папка", listOf("b", "a")))
    }

    @Test
    fun pagesSplitAndShrinkFirstPageWithWidgets() {
        assertThat(HomeLayoutOps.pages(0, hasWidgets = false)).containsExactly(IntRange.EMPTY)
        assertThat(HomeLayoutOps.pages(20, hasWidgets = false)).containsExactly(0 until 16, 16 until 20).inOrder()
        assertThat(HomeLayoutOps.pages(20, hasWidgets = true)).containsExactly(0 until 8, 8 until 20).inOrder()
    }

    @Test
    fun visibleEntriesKeepOriginalIndexes() {
        val l = HomeLayout(items = listOf(HomeItem.App("gone"), HomeItem.App("a"), HomeItem.Folder("f", "F", listOf("gone", "b"))))
        val v = HomeLayoutOps.visibleEntries(l.items, setOf("a", "b"))
        assertThat(v.map { it.index }).containsExactly(1, 2).inOrder()
        assertThat((v[1].value as HomeItem.Folder).apps).containsExactly("b")
    }

    @Test
    fun layoutSurvivesJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val l = HomeLayout(
            items = listOf(HomeItem.App("a"), HomeItem.Folder("f", "Игры", listOf("b", "c"))),
            widgets = listOf(7), initialized = true, coachmarkShown = true
        )
        val back = json.decodeFromString(HomeLayout.serializer(), json.encodeToString(HomeLayout.serializer(), l))
        assertThat(back).isEqualTo(l)
    }

    @Test
    fun ungroupPutsAppsInPlace() {
        val l = HomeLayout(items = listOf(HomeItem.App("x"), HomeItem.Folder("f", "F", listOf("a", "b")), HomeItem.App("y")))
        val r = HomeLayoutOps.ungroup(l, "f")
        assertThat(r.items.map { (it as HomeItem.App).pkg }).containsExactly("x", "a", "b", "y").inOrder()
    }

    @Test
    fun widgetResizeStepsAndClamps() {
        val l = HomeLayoutOps.addWidget(HomeLayout(), 3)
        val taller = HomeLayoutOps.resizeWidget(l, 3, currentDp = 144, steps = 1)
        assertThat(taller.widgetHeights[3]).isEqualTo(216)
        val clamped = HomeLayoutOps.resizeWidget(l, 3, currentDp = 72, steps = -1)
        assertThat(clamped.widgetHeights[3]).isEqualTo(HomeLayoutOps.WIDGET_MIN_DP)
        assertThat(HomeLayoutOps.removeWidget(taller, 3).widgetHeights).isEmpty()
    }

    @Test
    fun folderNamesAreCleaned() {
        assertThat(HomeLayoutOps.cleanFolderName("   ")).isEqualTo("Папка")
        assertThat(HomeLayoutOps.cleanFolderName("  Игры ")).isEqualTo("Игры")
        assertThat(HomeLayoutOps.cleanFolderName("x".repeat(50))).hasLength(30)
    }

    @Test
    fun crossPageDropGoesToEndOfTargetPage() {
        // Элементы 0..19: страница 2 = 16..19. Перенос элемента 3 на страницу 2 → после 19.
        val l = HomeLayout(items = (0 until 20).map { HomeItem.App("p$it") })
        val to = HomeLayoutOps.endOfPageTarget(from = 3, lastOnPage = 19, size = 20)
        val moved = HomeLayoutOps.move(l, 3, to)
        assertThat((moved.items.last() as HomeItem.App).pkg).isEqualTo("p3")
        // Обратно: элемент 18 на страницу 1 (последний на ней — 15) → встаёт на 16-е место.
        val back = HomeLayoutOps.move(l, 18, HomeLayoutOps.endOfPageTarget(18, 15, 20))
        assertThat((back.items[16] as HomeItem.App).pkg).isEqualTo("p18")
        assertThat(HomeLayoutOps.endOfPageTarget(2, null, 7)).isEqualTo(7)
    }

    @Test
    fun removeAtDropsFolder() {
        val l = HomeLayout(items = listOf(HomeItem.Folder("f", "F", listOf("a", "b")), HomeItem.App("c")))
        assertThat(HomeLayoutOps.removeAt(l, 0).items).containsExactly(HomeItem.App("c"))
        assertThat(HomeLayoutOps.removeAt(l, 9)).isEqualTo(l)
    }

    @Test
    fun movingAppFromOneFolderToAnotherDoesNotDuplicate() {
        val l = HomeLayout(
            items = listOf(
                HomeItem.Folder("a", "A", listOf("x", "y", "z")),
                HomeItem.Folder("b", "B", listOf("p", "q"))
            ),
            initialized = true
        )
        val r = HomeLayoutOps.addToFolder(l, "x", folderId = "b", newFolderId = "n")
        assertThat((r.items[0] as HomeItem.Folder).apps).containsExactly("y", "z").inOrder()
        assertThat((r.items[1] as HomeItem.Folder).apps).containsExactly("p", "q", "x").inOrder()
        assertThat(r.items.flatMap { if (it is HomeItem.Folder) it.apps else listOf((it as HomeItem.App).pkg) }.count { it == "x" }).isEqualTo(1)
    }
}
