package dev.loka.mr

/** Lens correction preset: k1/k2 are the radial barrel-distortion coefficients. */
class LensPreset(val nameAr: String, val k1: Float, val k2: Float)

/**
 * Values the wearer can change from inside the headset. Read on the GL thread,
 * written from the GL thread (launcher tiles) or the UI thread (volume keys),
 * so the indices are volatile.
 */
object Tuning {
    val ipdMillimetres = intArrayOf(58, 60, 63, 66, 69)
    val lenses = listOf(
        LensPreset("خفيف", 0.12f, 0.03f),
        LensPreset("قياسي", 0.20f, 0.08f),
        LensPreset("قوي", 0.30f, 0.14f),
    )

    /** Distance from the wearer to the windows, in metres. */
    const val WINDOW_DISTANCE = 1.4f

    /** The scene is rendered at this fraction of the screen to keep the phone cooler. */
    const val RENDER_SCALE = 0.85f

    @Volatile var ipdIndex = 2
    @Volatile var lensIndex = 1

    val ipdMetres: Float get() = ipdMillimetres[ipdIndex] / 1000f
    val lens: LensPreset get() = lenses[lensIndex]

    fun nextIpd() { ipdIndex = (ipdIndex + 1) % ipdMillimetres.size }
    fun nextLens() { lensIndex = (lensIndex + 1) % lenses.size }
}
