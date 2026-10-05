package com.justtracker.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The layer rules of docs/05_architecture.md §2, checked on the imports of the sources (ADR-28): a violation fails
 * the build instead of waiting for a review.
 */
class ArchitectureTest {
    private val root = File("src/main/java/com/justtracker/app")

    private fun sources(dir: String) = File(root, dir).walkTopDown().filter { it.extension == "kt" }.toList()

    private fun violations(dir: String, forbidden: Regex, allowed: Set<String> = emptySet()): List<String> =
        sources(dir).filter { it.name !in allowed }.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") && forbidden.containsMatchIn(it) }.map { "${f.name}: $it" }
        }

    @Test
    fun `the sources are where the test expects them`() {
        assertTrue(root.absolutePath, sources("domain").size > 20)
    }

    @Test
    fun `domain is plain Kotlin without Android or the outer layers`() {
        val v = violations("domain", Regex("""^import (android\.|androidx\.|com\.justtracker\.app\.(data|ui|service|di)\.)"""))
        assertTrue(v.joinToString("\n"), v.isEmpty())
    }

    @Test
    fun `service does not depend on the UI`() {
        val v = violations("service", Regex("""^import com\.justtracker\.app\.ui\."""))
        assertTrue(v.joinToString("\n"), v.isEmpty())
    }

    @Test
    fun `data does not depend on the UI or the service`() {
        val v = violations("data", Regex("""^import com\.justtracker\.app\.(ui|service)\."""))
        assertTrue(v.joinToString("\n"), v.isEmpty())
    }

    @Test
    fun `UI reaches the container only where view models and locals are built, and never Room entities`() {
        val container = violations(
            "ui", Regex("""^import com\.justtracker\.app\.(di\.AppContainer|ui\.common\.LocalAppContainer)$"""),
            allowed = setOf("ViewModelSupport.kt", "JustTrackerApp.kt", "MainActivity.kt"),
        )
        val entities = violations("ui", Regex("""^import com\.justtracker\.app\.data\.db\."""))
        assertTrue((container + entities).joinToString("\n"), container.isEmpty() && entities.isEmpty())
    }
}
