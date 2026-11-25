package dev.loka.mr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * A flat rectangle in the room. It is painted with an Android [Canvas] into a
 * bitmap, and the bitmap lives on the GPU as a texture.
 */
class Panel(val pxWidth: Int, val pxHeight: Int, val widthMetres: Float) {

    val heightMetres: Float = widthMetres * pxHeight / pxWidth
    val bitmap: Bitmap = Bitmap.createBitmap(pxWidth, pxHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    private var texture = 0

    /** Call on the GL thread whenever the GL context is (re)created. */
    fun initGl() {
        texture = Gl.texture2d()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** Pushes the current bitmap pixels to the GPU. */
    fun upload() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    }

    fun bind() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }
}
