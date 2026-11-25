package dev.loka.mr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One frame, in order:
 *  1. ARCore gives the camera picture and the phone's pose.
 *  2. For each eye, the camera picture is drawn, then the windows, seen from a
 *     point 31 mm to the left or right of the phone's camera.
 *  3. Both eyes (side by side, in an off-screen texture) are bent by a barrel
 *     shader so the viewer's lenses bend them back straight.
 */
class StereoRenderer(private val activity: MainActivity) : GLSurfaceView.Renderer {

    // ---- windows -----------------------------------------------------------------------------
    private val launcherPanel = Panel(1024, 640, 0.96f)
    private val statusPanel = Panel(512, 256, 0.42f)
    private val bannerPanel = Panel(768, 128, 0.60f)
    private val ringPanel = Panel(64, 64, 1f)
    private val dotPanel = Panel(64, 64, 1f)
    private val launcher = LauncherUi(launcherPanel)

    private var launcherAnchor: Anchor? = null
    private var statusAnchor: Anchor? = null

    @Volatile private var placeRequested = true
    private var everPlaced = false

    // ---- GL objects --------------------------------------------------------------------------
    private var cameraTexture = 0
    private var cameraTextureBound = false
    private var backgroundProgram = 0
    private var quadProgram = 0
    private var distortProgram = 0
    private var fbo = 0
    private var sceneTexture = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var sceneWidth = 0
    private var sceneHeight = 0
    private var eyeWidth = 0
    private var lastRotation = -1

    private val ndcQuad = Gl.floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val cameraUv = Gl.floats(FloatArray(8))
    private val fullscreenUv = Gl.floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val unitQuad = Gl.floats(floatArrayOf(-0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f))
    private val unitQuadUv = Gl.floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // ---- per-frame scratch (no allocation in the hot path) -----------------------------------
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val shift = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val launcherMatrix = FloatArray(16)
    private val statusMatrix = FloatArray(16)
    private val inverse = FloatArray(16)
    private val rayOrigin = FloatArray(4)
    private val rayDirection = FloatArray(4)
    private val localOrigin = FloatArray(4)
    private val localDirection = FloatArray(4)
    private val hit = FloatArray(3)

    // ---- interaction state -------------------------------------------------------------------
    private var hoverTile = -1
    private var dwell = 0f
    private var lockedOut = false
    private var gazeDistance = -1f
    private var message: String? = null
    private var messageUntilNs = 0L
    private var lastLauncherKey = ""
    private var lastLauncherDrawNs = 0L

    private var lastFrameNs = 0L
    private var frames = 0
    private var fps = 0
    private var fpsWindowStartNs = 0L
    private var lastStatusNs = 0L
    private var clock = ""
    private var lastClockNs = 0L
    private var bannerText = ""
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun requestRecenter() { placeRequested = true }

    // ------------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        cameraTextureBound = false

        backgroundProgram = Gl.program(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
        quadProgram = Gl.program(QUAD_VERTEX, QUAD_FRAGMENT)
        distortProgram = Gl.program(DISTORT_VERTEX, DISTORT_FRAGMENT)

        for (p in listOf(launcherPanel, statusPanel, bannerPanel, ringPanel, dotPanel)) p.initGl()
        Hud.drawRing(ringPanel)
        Hud.drawDot(dotPanel)
        lastLauncherKey = ""
        bannerText = ""
        fbo = 0
        sceneTexture = 0
        lastRotation = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        sceneWidth = ((width * Tuning.RENDER_SCALE).toInt()) and 1.inv()
        sceneHeight = (height * Tuning.RENDER_SCALE).toInt()
        eyeWidth = sceneWidth / 2

        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(sceneTexture), 0)
        }
        sceneTexture = Gl.texture2d()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sceneWidth, sceneHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sceneTexture, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        lastRotation = -1
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = activity.session
        if (session == null || sceneWidth == 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        if (!cameraTextureBound) {
            session.setCameraTextureName(cameraTexture)
            cameraTextureBound = true
        }
        val rotation = activity.displayRotation()
        if (rotation != lastRotation) {
            // Each eye is half of the scene, so the camera picture is cropped to that shape.
            session.setDisplayGeometry(rotation, eyeWidth, sceneHeight)
            lastRotation = rotation
        }

        val frame = try {
            session.update()
        } catch (e: Exception) {
            return
        }
        val camera = frame.camera

        if (frame.hasDisplayGeometryChanged()) {
            ndcQuad.rewind()
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                Coordinates2d.TEXTURE_NORMALIZED, cameraUv,
            )
            ndcQuad.rewind()
            cameraUv.rewind()
        }

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now
        countFps(now)
        if (now - lastClockNs > 1_000_000_000L || clock.isEmpty()) {
            clock = clockFormat.format(Date())
            lastClockNs = now
        }

        val tracking = camera.trackingState == TrackingState.TRACKING
        val cameraPose = camera.displayOrientedPose

        if (tracking && placeRequested) placeWindows(cameraPose)

        val launcherVisible = launcherAnchor?.trackingState == TrackingState.TRACKING
        val statusVisible = statusAnchor?.trackingState == TrackingState.TRACKING
        if (launcherVisible) launcherAnchor!!.pose.toMatrix(launcherMatrix, 0)
        if (statusVisible) statusAnchor!!.pose.toMatrix(statusMatrix, 0)

        updateGaze(tracking && launcherVisible, cameraPose, dt, now)
        refreshPanels(now, tracking, launcherVisible)

        // ---- draw both eyes into the off-screen scene --------------------------------------
        camera.getProjectionMatrix(projection, 0, 0.05f, 30f)
        camera.getViewMatrix(view, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        for (eye in 0..1) {
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, sceneHeight)
            drawBackground()

            // The left eye sits left of the camera, so the world appears shifted right (+x).
            val offset = if (eye == 0) Tuning.ipdMetres / 2f else -Tuning.ipdMetres / 2f
            Matrix.setIdentityM(shift, 0)
            Matrix.translateM(shift, 0, offset, 0f, 0f)
            Matrix.multiplyMM(eyeView, 0, shift, 0, view, 0)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, eyeView, 0)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            if (launcherVisible) drawWorldPanel(launcherPanel, launcherMatrix)
            if (statusVisible) drawWorldPanel(statusPanel, statusMatrix)
            if (!tracking || !everPlaced) drawHeadLocked(bannerPanel, offset, 1.0f, 0.60f, 0.15f)
            if (tracking) drawReticle(offset)

            GLES20.glDisable(GLES20.GL_BLEND)
        }

        drawDistorted()
    }

    // ---- world ---------------------------------------------------------------------------------

    /** Puts the windows in front of the wearer, level with the floor, whichever way the phone points. */
    private fun placeWindows(cameraPose: Pose) {
        val backward = cameraPose.getTransformedAxis(2, 1f)
        if (hypot(backward[0], backward[2]) < 0.15f) return // looking straight up or down: wait

        val yaw = atan2(backward[0], backward[2])
        val t = cameraPose.translation
        val base = Pose(
            floatArrayOf(t[0], t[1], t[2]),
            floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f)),
        )
        val d = Tuning.WINDOW_DISTANCE
        val side = 0.62f // about 36 degrees to the left

        val launcherPose = base.compose(Pose.makeTranslation(0f, 0f, -d))
        val statusPose = base
            .compose(Pose.makeRotation(0f, sin(side / 2f), 0f, cos(side / 2f)))
            .compose(Pose.makeTranslation(0f, -0.20f, -d))

        val session = activity.session ?: return
        try {
            val newLauncher = session.createAnchor(launcherPose)
            val newStatus = session.createAnchor(statusPose)
            launcherAnchor?.detach()
            statusAnchor?.detach()
            launcherAnchor = newLauncher
            statusAnchor = newStatus
            placeRequested = false
            everPlaced = true
        } catch (e: Exception) {
            // Not tracking at this instant; try again next frame.
        }
    }

    /** Casts a ray along the wearer's gaze and finds the launcher tile it lands on. */
    private fun updateGaze(canHit: Boolean, cameraPose: Pose, dt: Float, now: Long) {
        var tile = -1
        gazeDistance = -1f
        if (canHit && rayHitsPanel(cameraPose, launcherMatrix, launcherPanel)) {
            gazeDistance = hit[2]
            tile = launcher.hit(hit[0], hit[1])
        }

        if (tile != hoverTile) {
            hoverTile = tile
            dwell = 0f
            lockedOut = false
        }
        if (hoverTile >= 0 && !lockedOut) {
            dwell += dt
            if (dwell >= DWELL_SECONDS) {
                trigger(launcher.tiles[hoverTile], now)
                lockedOut = true
                dwell = 0f
            }
        }
    }

    private fun rayHitsPanel(cameraPose: Pose, panelMatrix: FloatArray, panel: Panel): Boolean {
        val origin = cameraPose.translation
        val backward = cameraPose.getTransformedAxis(2, 1f)
        rayOrigin[0] = origin[0]; rayOrigin[1] = origin[1]; rayOrigin[2] = origin[2]; rayOrigin[3] = 1f
        rayDirection[0] = -backward[0]; rayDirection[1] = -backward[1]; rayDirection[2] = -backward[2]; rayDirection[3] = 0f

        if (!Matrix.invertM(inverse, 0, panelMatrix, 0)) return false
        Matrix.multiplyMV(localOrigin, 0, inverse, 0, rayOrigin, 0)
        Matrix.multiplyMV(localDirection, 0, inverse, 0, rayDirection, 0)

        if (abs(localDirection[2]) < 1e-5f) return false
        val t = -localOrigin[2] / localDirection[2]
        if (t <= 0f) return false
        val x = localOrigin[0] + t * localDirection[0]
        val y = localOrigin[1] + t * localDirection[1]
        if (abs(x) > panel.widthMetres / 2f || abs(y) > panel.heightMetres / 2f) return false

        hit[0] = x / panel.widthMetres + 0.5f
        hit[1] = 0.5f - y / panel.heightMetres
        hit[2] = t
        return true
    }

    private fun trigger(tile: Tile, now: Long) {
        when (tile.action) {
            Action.RECENTER -> { placeRequested = true; say("تم التمركز", now) }
            Action.IPD -> { Tuning.nextIpd(); say("${Tuning.ipdMillimetres[Tuning.ipdIndex]} مم", now) }
            Action.LENS -> { Tuning.nextLens(); say("العدسة: ${Tuning.lens.nameAr}", now) }
            else -> say("${tile.titleAr}: في المرحلة القادمة", now)
        }
    }

    private fun say(text: String, now: Long) {
        message = text
        messageUntilNs = now + 2_500_000_000L
    }

    // ---- panel content -------------------------------------------------------------------------

    private fun refreshPanels(now: Long, tracking: Boolean, launcherVisible: Boolean) {
        val shown = if (now < messageUntilNs) message else null
        val step = (dwell / DWELL_SECONDS * 20f).toInt()
        val key = "$hoverTile|$step|$shown|$clock|${Tuning.ipdIndex}|${Tuning.lensIndex}"
        if (launcherVisible && key != lastLauncherKey && now - lastLauncherDrawNs > 40_000_000L) {
            launcher.draw(LauncherState(hoverTile, (dwell / DWELL_SECONDS).coerceIn(0f, 1f), shown, clock))
            lastLauncherKey = key
            lastLauncherDrawNs = now
        }

        if (now - lastStatusNs > 500_000_000L) {
            Hud.drawStatus(statusPanel, if (tracking) "نشط" else "مفقود", fps)
            lastStatusNs = now
        }

        val wanted = when {
            !everPlaced -> "حرّك الموبايل ببطء لتهيئة التتبع"
            !tracking -> "فُقد التتبع — حرّك الموبايل ببطء"
            else -> ""
        }
        if (wanted != bannerText && wanted.isNotEmpty()) Hud.drawBanner(bannerPanel, wanted)
        bannerText = wanted
    }

    private fun countFps(now: Long) {
        frames++
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
        if (now - fpsWindowStartNs >= 1_000_000_000L) {
            fps = frames
            frames = 0
            fpsWindowStartNs = now
        }
    }

    // ---- drawing -------------------------------------------------------------------------------

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram, "uTexture"), 0)

        val pos = GLES20.glGetAttribLocation(backgroundProgram, "aPos")
        val uv = GLES20.glGetAttribLocation(backgroundProgram, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, ndcQuad)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, cameraUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun drawWorldPanel(panel: Panel, anchorMatrix: FloatArray) {
        System.arraycopy(anchorMatrix, 0, model, 0, 16)
        Matrix.scaleM(model, 0, panel.widthMetres, panel.heightMetres, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        drawQuad(panel, mvp, 1f)
    }

    /** A panel that stays put in front of the eyes, [distance] metres away. */
    private fun drawHeadLocked(panel: Panel, eyeOffset: Float, distance: Float, width: Float, height: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, eyeOffset, 0f, 0f)
        Matrix.translateM(model, 0, 0f, 0f, -distance)
        Matrix.scaleM(model, 0, width, height, 1f)
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)
        drawQuad(panel, mvp, 1f)
    }

    private fun drawReticle(eyeOffset: Float) {
        val distance = if (gazeDistance > 0f) gazeDistance - 0.01f else Tuning.WINDOW_DISTANCE
        val ring = distance * 0.045f
        val progress = (dwell / DWELL_SECONDS).coerceIn(0f, 1f)
        val dot = ring * (0.22f + 0.60f * progress)
        drawHeadLocked(ringPanel, eyeOffset, distance, ring, ring)
        drawHeadLocked(dotPanel, eyeOffset, distance, dot, dot)
    }

    private fun drawQuad(panel: Panel, matrix: FloatArray, alpha: Float) {
        GLES20.glUseProgram(quadProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        panel.bind()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(quadProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(quadProgram, "uMvp"), 1, false, matrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(quadProgram, "uAlpha"), alpha)

        val pos = GLES20.glGetAttribLocation(quadProgram, "aPos")
        val uv = GLES20.glGetAttribLocation(quadProgram, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, unitQuad)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, unitQuadUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    /** Final pass: bend each half of the scene so the viewer's lenses straighten it. */
    private fun drawDistorted() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(distortProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(distortProgram, "uScene"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distortProgram, "uK1"), Tuning.lens.k1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distortProgram, "uK2"), Tuning.lens.k2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distortProgram, "uAspect"), eyeWidth.toFloat() / sceneHeight)

        val pos = GLES20.glGetAttribLocation(distortProgram, "aPos")
        val uv = GLES20.glGetAttribLocation(distortProgram, "aUv")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, ndcQuad)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, fullscreenUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private companion object {
        const val DWELL_SECONDS = 1.2f

        const val BACKGROUND_VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = aUv;
            }
        """

        const val BACKGROUND_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
        """

        const val QUAD_VERTEX = """
            uniform mat4 uMvp;
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
                vUv = aUv;
            }
        """

        const val QUAD_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv) * uAlpha;
            }
        """

        const val DISTORT_VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = aUv;
            }
        """

        const val DISTORT_FRAGMENT = """
            precision highp float;
            uniform sampler2D uScene;
            uniform float uK1;
            uniform float uK2;
            uniform float uAspect;
            varying vec2 vUv;
            void main() {
                float eye = step(0.5, vUv.x);
                vec2 local = vec2(vUv.x * 2.0 - eye, vUv.y);
                vec2 c = local * 2.0 - 1.0;
                c.x *= uAspect;
                float r2 = dot(c, c);
                c *= 1.0 + uK1 * r2 + uK2 * r2 * r2;
                c.x /= uAspect;
                vec2 s = c * 0.5 + 0.5;
                if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                gl_FragColor = vec4(texture2D(uScene, vec2((s.x + eye) * 0.5, s.y)).rgb, 1.0);
            }
        """
    }
}
