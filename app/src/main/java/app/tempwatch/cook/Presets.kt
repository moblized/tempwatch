package app.tempwatch.cook

/** One doneness choice, e.g. "Medium rare · 135°F". */
data class Doneness(val label: String, val targetF: Int, val note: String? = null)

data class MeatPreset(
    val id: String,
    val name: String,
    /** USDA safe minimum internal temperature for this meat, °F. */
    val usdaMinF: Int?,
    val usdaNote: String?,
    val options: List<Doneness>,
) {
    fun isBelowUsda(d: Doneness): Boolean = usdaMinF != null && d.targetF < usdaMinF
}

/**
 * Meat and doneness targets (pull temperatures, in °F). USDA minimums: whole cuts of beef,
 * pork, lamb and veal 145°F with a 3-minute rest; ground meat 160°F; poultry 165°F;
 * fish 145°F. Chef-style doneness below those is offered but flagged in the picker.
 */
object Presets {
    val all: List<MeatPreset> = listOf(
        MeatPreset(
            "beef", "Beef", 145, "USDA: 145°F + 3 min rest",
            listOf(
                Doneness("Rare", 125),
                Doneness("Medium rare", 135),
                Doneness("Medium", 145),
                Doneness("Medium well", 150),
                Doneness("Well done", 160),
            ),
        ),
        MeatPreset(
            "pork", "Pork", 145, "USDA: 145°F + 3 min rest",
            listOf(
                Doneness("Medium (juicy)", 145),
                Doneness("Well done", 160),
            ),
        ),
        MeatPreset(
            "chicken", "Chicken", 165, "USDA: 165°F",
            listOf(
                Doneness("Breast", 165),
                Doneness("Thighs & legs", 175, "Safe at 165°F; more tender at 175°F+"),
                Doneness("Whole bird (thigh)", 170),
            ),
        ),
        MeatPreset(
            "turkey", "Turkey", 165, "USDA: 165°F",
            listOf(
                Doneness("Breast", 165),
                Doneness("Thighs & legs", 175, "Safe at 165°F; more tender at 175°F+"),
            ),
        ),
        MeatPreset(
            "lamb", "Lamb", 145, "USDA: 145°F + 3 min rest",
            listOf(
                Doneness("Rare", 125),
                Doneness("Medium rare", 135),
                Doneness("Medium", 145),
                Doneness("Well done", 160),
            ),
        ),
        MeatPreset(
            "ground", "Ground meat & burgers", null, "USDA: 160°F (beef, pork, lamb), 165°F (poultry)",
            listOf(
                Doneness("Beef, pork or lamb", 160),
                Doneness("Chicken or turkey", 165),
            ),
        ),
        MeatPreset(
            "fish", "Fish", 145, "USDA: 145°F",
            listOf(
                Doneness("Salmon, medium", 125),
                Doneness("Fish, done", 145),
            ),
        ),
        MeatPreset(
            "bbq", "BBQ low & slow", null, "Cooked well past safe; these are tender-pull temps",
            listOf(
                Doneness("Brisket", 203),
                Doneness("Pulled pork", 203),
                Doneness("Pork ribs", 198),
                Doneness("Beef short ribs", 205),
            ),
        ),
        MeatPreset(
            "ham", "Ham", null, "USDA: 145°F fresh, 140°F reheating fully cooked",
            listOf(
                Doneness("Fully cooked, reheat", 140),
                Doneness("Fresh / raw", 145),
            ),
        ),
    )

    fun title(preset: MeatPreset, d: Doneness): String = "${preset.name} · ${d.label}"
}
