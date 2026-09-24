package org.openscore.health

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `apis/<sport>/<league>/health.json` must be internally consistent with the docs next to
 * it: the samples it names exist, the key paths it requires are present in those samples (so a
 * check can only fail because the API changed, not because the expectation was wrong), and every
 * placeholder resolves.
 */
class CheckFilesTest {
    private val root = repoRoot()
    private val files = CheckFiles.discover(File(root, "apis"))

    @Test
    fun everyMappedLeagueHasAHealthFile() {
        val leagueDirs = File(root, "apis").listFiles { f -> f.isDirectory && !f.name.startsWith("_") }!!
            .flatMap { sport -> sport.listFiles { f -> f.isDirectory }!!.toList() }
        val missing = leagueDirs
            .filter { File(it, "README.md").isFile && !File(it, CheckFiles.FILE_NAME).isFile }
            // An access audit records why a feed cannot be used; it has no endpoint to check.
            .filterNot { outOfScope(File(it, "README.md")) }
        assertTrue(missing.isEmpty(), "leagues without health.json: ${missing.map { it.relativeTo(root).path }}")
        assertTrue(files.size >= 15, "expected at least 15 health files, found ${files.size}")
    }

    /** True when the README's header table marks the feed out of scope (`| **Status** | ⛔ out of scope: …`). */
    private fun outOfScope(readme: File): Boolean = readme.useLines { lines ->
        lines.any { it.startsWith("| **Status**") && it.contains("out of scope", ignoreCase = true) }
    }

    @Test
    fun samplesExistAndRequiredKeysArePresentInThem() {
        val problems = ArrayList<String>()
        for (f in files) {
            assertTrue(f.readme.isFile, "${f.league}: README.md missing next to health.json")
            for (c in f.checks) {
                val sample = c.sample?.let { File(f.samplesDir, it) }
                if (sample != null && !sample.isFile) {
                    problems += "${f.league} / ${c.label}: sample ${c.sample} not found"
                    continue
                }
                if (sample == null || c.keys.isEmpty()) continue
                val absent = when (c.format) {
                    "json" -> {
                        val element = Shape.unwrapSample(Shape.parseJson(sample.readText()))
                        c.keys.filterNot { Shape.hasJsonPath(element, it) }
                    }
                    "xml" -> {
                        val node = Shape.parseXml(sample.readText())
                        c.keys.filterNot { Shape.hasXmlPath(node, it) }
                    }
                    else -> emptyList()
                }
                if (absent.isNotEmpty()) problems += "${f.league} / ${c.label}: keys not in samples/${c.sample}: $absent"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun placeholdersResolve() = runBlocking {
        val placeholders = Placeholders(LocalDate(2026, 9, 12), SecretResolver { if (it.startsWith("laliga.")) "k" else null })
        for (f in files) for (c in f.checks) {
            val resolved = placeholders.resolve(c.path ?: c.url ?: c.query!!)
            assertTrue(!resolved.contains('{') || c.query != null, "${f.league} / ${c.label}: unresolved placeholder in $resolved")
            (f.headers + c.headers).values.forEach { placeholders.resolve(it) }
        }
    }

    @Test
    fun checkNamesAreUniquePerLeague() {
        for (f in files) {
            val dupes = f.checks.groupBy { it.label }.filterValues { it.size > 1 }.keys
            assertTrue(dupes.isEmpty(), "${f.league}: duplicate check names $dupes")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "apis").isDirectory && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("repo root not found")
    }
}
