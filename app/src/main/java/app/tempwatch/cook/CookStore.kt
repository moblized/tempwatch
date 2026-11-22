package app.tempwatch.cook

import android.content.Context
import java.io.File

/**
 * Cooks on disk (app-private storage): `cooks/active.cook` for the one in progress, so it
 * survives the app being closed or the phone killing it, and `cooks/<start time>.cook` for
 * each finished one.
 */
object CookStore {
    private const val EXT = ".cook"
    private const val ACTIVE = "active$EXT"

    private fun dir(context: Context) = File(context.filesDir, "cooks").apply { mkdirs() }

    @Synchronized
    fun saveActive(context: Context, record: CookRecord) = writeAtomic(File(dir(context), ACTIVE), CookFile.encode(record))

    @Synchronized
    fun loadActive(context: Context): CookRecord? = read(File(dir(context), ACTIVE), withSamples = true)

    /** Moves a cook into history. Accidental starts (under a minute, almost no data) aren't kept. */
    @Synchronized
    fun finish(context: Context, record: CookRecord) {
        val end = record.endedAt ?: System.currentTimeMillis()
        if (record.samples.size >= 3 && end - record.startedAt >= 60_000L) {
            writeAtomic(File(dir(context), "${record.startedAt}$EXT"), CookFile.encode(record))
        }
        File(dir(context), ACTIVE).delete()
    }

    /** Finished cooks, newest first, header only (no samples). */
    @Synchronized
    fun list(context: Context): List<CookRecord> =
        (dir(context).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(EXT) && it.name != ACTIVE }
            .mapNotNull { read(it, withSamples = false) }
            .sortedByDescending { it.startedAt }

    @Synchronized
    fun load(context: Context, id: Long): CookRecord? = read(File(dir(context), "$id$EXT"), withSamples = true)

    @Synchronized
    fun delete(context: Context, id: Long) {
        File(dir(context), "$id$EXT").delete()
    }

    private fun read(file: File, withSamples: Boolean): CookRecord? = runCatching {
        if (!file.exists()) null else file.bufferedReader().useLines { CookFile.decode(it, withSamples) }
    }.getOrNull()

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
