package dev.loka.mr

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface

enum class Action { RECENTER, IPD, LENS, BROWSER, YOUTUBE, VOICE }

class Tile(val titleAr: String, val caption: String, val action: Action)

/** What the launcher needs to know to paint itself. */
class LauncherState(
    val hoverTile: Int,
    val dwellProgress: Float,
    val message: String?,
    val clock: String,
)

object Palette {
    val ink = Color.rgb(0x10, 0x11, 0x14)
    val surface = Color.rgb(0x1B, 0x1D, 0x22)
    val surfaceHover = Color.rgb(0x27, 0x2A, 0x31)
    val hairline = Color.rgb(0x3A, 0x3D, 0x44)
    val text = Color.rgb(0xF2, 0xF0, 0xEA)
    val muted = Color.rgb(0x9A, 0x9F, 0xA8)
    val amber = Color.rgb(0xF2, 0xA9, 0x3B)
    val panel = Color.argb(0xDB, 0x10, 0x11, 0x14)
}

/**
 * The launcher window: six tiles, laid out right-to-left because the labels are
 * Arabic. Three tiles already do something (recentre, eye distance, lens);
 * the other three are placeholders for the next stages.
 */
class LauncherUi(private val panel: Panel) {

    val tiles = listOf(
        Tile("إعادة التمركز", "Recenter", Action.RECENTER),
        Tile("المسافة بين العينين", "IPD", Action.IPD),
        Tile("العدسة", "Lens", Action.LENS),
        Tile("المتصفح", "Browser", Action.BROWSER),
        Tile("يوتيوب", "YouTube", Action.YOUTUBE),
        Tile("الأوامر الصوتية", "Voice", Action.VOICE),
    )

    private val columns = 3
    private val rows = 2
    private val margin = 40f
    private val gap = 20f
    private val headerHeight = 128f

    private val tileWidth = (panel.pxWidth - 2 * margin - (columns - 1) * gap) / columns
    private val tileHeight = (panel.pxHeight - headerHeight - margin - (rows - 1) * gap) / rows

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = 34f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.amber
        textSize = 30f
        textAlign = Paint.Align.LEFT
    }

    /** Right-to-left grid: tile 0 is the top-right one. */
    fun tileRect(index: Int): RectF {
        val col = index % columns
        val row = index / columns
        val right = panel.pxWidth - margin - col * (tileWidth + gap)
        val top = headerHeight + row * (tileHeight + gap)
        return RectF(right - tileWidth, top, right, top + tileHeight)
    }

    /** [u] and [v] are 0..1 across the panel, with v counted from the top. */
    fun hit(u: Float, v: Float): Int {
        val x = u * panel.pxWidth
        val y = v * panel.pxHeight
        for (i in tiles.indices) if (tileRect(i).contains(x, y)) return i
        return -1
    }

    private fun captionFor(tile: Tile): String = when (tile.action) {
        Action.IPD -> "${Tuning.ipdMillimetres[Tuning.ipdIndex]} مم"
        Action.LENS -> Tuning.lens.nameAr
        Action.RECENTER -> tile.caption
        else -> "${tile.caption} · قريباً"
    }

    fun draw(state: LauncherState) {
        val c = panel.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        fill.color = Palette.panel
        c.drawRoundRect(0f, 0f, panel.pxWidth.toFloat(), panel.pxHeight.toFloat(), 14f, 14f, fill)

        // Header: brand on the right, clock and the last message on the left.
        fill.color = Palette.amber
        c.drawRect(panel.pxWidth - margin - 14f, 46f, panel.pxWidth - margin, 60f, fill)
        c.drawText("Loka MR", panel.pxWidth - margin - 30f, 62f, brand)
        c.drawText(state.clock, margin, 62f, clockPaint)
        state.message?.let { c.drawText(it, margin + 130f, 62f, messagePaint) }
        fill.color = Palette.hairline
        c.drawRect(margin, headerHeight - 28f, panel.pxWidth - margin, headerHeight - 26f, fill)

        for (i in tiles.indices) {
            val r = tileRect(i)
            val hovered = i == state.hoverTile
            fill.color = if (hovered) Palette.surfaceHover else Palette.surface
            c.drawRect(r, fill)

            if (hovered) {
                fill.color = Palette.amber
                c.drawRect(r.right - 8f, r.top, r.right, r.bottom, fill)
                // Dwell progress runs right to left along the bottom edge.
                c.drawRect(r.right - r.width() * state.dwellProgress, r.bottom - 6f, r.right, r.bottom, fill)
            }

            c.drawText(tiles[i].titleAr, r.right - 32f, r.top + 92f, title)
            c.drawText(captionFor(tiles[i]), r.right - 32f, r.top + 140f, caption)
        }
        panel.upload()
    }
}

/** The two small panels that are not the launcher. */
object Hud {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = 28f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = 38f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun drawStatus(panel: Panel, tracking: String, fps: Int) {
        val c = panel.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        fill.color = Palette.panel
        c.drawRoundRect(0f, 0f, panel.pxWidth.toFloat(), panel.pxHeight.toFloat(), 14f, 14f, fill)

        val rows = listOf(
            "التتبع" to tracking,
            "الإطارات" to "$fps fps",
            "العينين" to "${Tuning.ipdMillimetres[Tuning.ipdIndex]} mm",
            "العدسة" to Tuning.lens.nameAr,
        )
        var y = 62f
        for ((name, v) in rows) {
            c.drawText(name, panel.pxWidth - 32f, y, label)
            c.drawText(v, 32f, y, value)
            y += 52f
        }
        panel.upload()
    }

    fun drawBanner(panel: Panel, text: String) {
        val c = panel.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        fill.color = Palette.panel
        c.drawRoundRect(0f, 0f, panel.pxWidth.toFloat(), panel.pxHeight.toFloat(), 14f, 14f, fill)
        fill.color = Palette.amber
        c.drawRect(0f, 0f, 10f, panel.pxHeight.toFloat(), fill)
        c.drawText(text, panel.pxWidth / 2f, panel.pxHeight / 2f + 13f, banner)
        panel.upload()
    }

    /** A hollow ring and a solid dot for the gaze cursor. */
    fun drawRing(panel: Panel) {
        val c = panel.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 6f
        fill.color = Palette.text
        c.drawCircle(panel.pxWidth / 2f, panel.pxHeight / 2f, panel.pxWidth / 2f - 6f, fill)
        fill.style = Paint.Style.FILL
        panel.upload()
    }

    fun drawDot(panel: Panel) {
        val c = panel.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        fill.color = Palette.amber
        c.drawCircle(panel.pxWidth / 2f, panel.pxHeight / 2f, panel.pxWidth / 2f - 2f, fill)
        panel.upload()
    }
}
