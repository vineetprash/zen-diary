package app.zendiary.io.importer

import app.zendiary.io.ImportBatch
import java.io.File

/**
 * Open/closed extension point: support a new import format by adding an Importer to
 * [ImporterRegistry]. `probe` must be cheap and side-effect free.
 */
interface Importer {
    val id: String
    val label: String
    suspend fun probe(file: File, displayName: String): Boolean
    suspend fun read(file: File): ImportBatch
}

class ImporterRegistry(val all: List<Importer>) {
    suspend fun detect(file: File, name: String): Importer? = all.firstOrNull { runCatching { it.probe(file, name) }.getOrDefault(false) }
    val supportedLabels: String get() = all.joinToString(", ") { it.label }
}
