package app.tempwatch.cook

/** A cook, live or finished. Temperatures in °C, times in wall-clock ms. */
data class CookRecord(
    val title: String,
    val targetC: Double,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** When the meat first hit the target, if it did. */
    val reachedAt: Long? = null,
    /** Booster's Bluetooth address and which of its probes this cook follows. */
    val device: String? = null,
    val probe: Int = 0,
    val samples: List<Sample> = emptyList(),
    /** Filled in when read back from a file's header (so history lists needn't load samples). */
    val savedPeakInternalC: Double? = null,
    val savedPeakAmbientC: Double? = null,
    val savedSampleCount: Int? = null,
) {
    val id: Long get() = startedAt
    val peakInternalC: Double? get() = samples.mapNotNull { it.internalC }.maxOrNull() ?: savedPeakInternalC
    val peakAmbientC: Double? get() = samples.mapNotNull { it.ambientC }.maxOrNull() ?: savedPeakAmbientC
    val startInternalC: Double? get() = samples.firstOrNull { it.internalC != null }?.internalC
    fun durationMs(now: Long): Long = (endedAt ?: now) - startedAt
}

/**
 * Plain-text cook file: a few `key=value` header lines, then one CSV row per sample.
 *
 *     tempwatch-cook 1
 *     title=Beef · Medium rare
 *     targetC=57.22
 *     ...
 *     samples
 *     1732311000000,22,25
 */
object CookFile {
    private const val MAGIC = "tempwatch-cook 1"
    private const val SAMPLES = "samples"

    fun encode(r: CookRecord): String = buildString {
        appendLine(MAGIC)
        appendLine("title=${r.title.replace('\n', ' ').replace('\r', ' ')}")
        appendLine("targetC=${r.targetC}")
        appendLine("startedAt=${r.startedAt}")
        appendLine("endedAt=${r.endedAt ?: ""}")
        appendLine("reachedAt=${r.reachedAt ?: ""}")
        appendLine("device=${r.device ?: ""}")
        appendLine("probe=${r.probe}")
        appendLine("peakInternalC=${r.peakInternalC ?: ""}")
        appendLine("peakAmbientC=${r.peakAmbientC ?: ""}")
        appendLine("sampleCount=${r.samples.size}")
        appendLine(SAMPLES)
        for (s in r.samples) {
            append(s.t).append(',').append(num(s.internalC)).append(',').append(num(s.ambientC)).append('\n')
        }
    }

    /** Reads a cook back. With [withSamples] false only the header is parsed (fast, for lists). */
    fun decode(lines: Sequence<String>, withSamples: Boolean = true): CookRecord? {
        val it = lines.iterator()
        if (!it.hasNext() || it.next().trim() != MAGIC) return null
        val header = HashMap<String, String>()
        while (it.hasNext()) {
            val line = it.next()
            if (line.trim() == SAMPLES) break
            val eq = line.indexOf('=')
            if (eq > 0) header[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val samples = ArrayList<Sample>()
        if (withSamples) {
            while (it.hasNext()) {
                val parts = it.next().split(',')
                if (parts.size < 3) continue
                val t = parts[0].trim().toLongOrNull() ?: continue
                samples += Sample(t, parts[1].trim().toDoubleOrNull(), parts[2].trim().toDoubleOrNull())
            }
        }
        return CookRecord(
            title = header["title"] ?: "Cook",
            targetC = header["targetC"]?.toDoubleOrNull() ?: return null,
            startedAt = header["startedAt"]?.toLongOrNull() ?: return null,
            endedAt = header["endedAt"]?.toLongOrNull(),
            reachedAt = header["reachedAt"]?.toLongOrNull(),
            device = header["device"]?.takeIf { it.isNotBlank() },
            probe = header["probe"]?.toIntOrNull() ?: 0,
            samples = samples,
            savedPeakInternalC = header["peakInternalC"]?.toDoubleOrNull(),
            savedPeakAmbientC = header["peakAmbientC"]?.toDoubleOrNull(),
            savedSampleCount = header["sampleCount"]?.toIntOrNull(),
        )
    }

    fun decode(text: String, withSamples: Boolean = true): CookRecord? = decode(text.lineSequence(), withSamples)

    private fun num(v: Double?): String = when {
        v == null -> ""
        v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
        else -> v.toString()
    }
}
