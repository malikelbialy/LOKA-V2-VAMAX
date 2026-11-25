package dev.loka.mr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.EnumSet
import kotlin.math.abs

class MainActivity : Activity() {

    @Volatile
    var session: Session? = null
        private set

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: StereoRenderer
    private var installRequested = false
    private var glResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        renderer = StereoRenderer(this)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()

        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                    return
                }
                session = createSession()
            } catch (e: UnavailableUserDeclinedInstallationException) {
                fail("لازم تثبّت Google Play Services for AR")
                return
            } catch (e: Exception) {
                fail("ARCore مش شغال على الموبايل ده: ${e.javaClass.simpleName}")
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            fail("الكاميرا مشغولة بتطبيق تاني")
            return
        }
        glView.onResume()
        glResumed = true
    }

    override fun onPause() {
        super.onPause()
        if (glResumed) {
            glView.onPause()
            glResumed = false
        }
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            fail("التطبيق محتاج صلاحية الكاميرا")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Volume up recentres the windows; holding it cycles the eye distance. Volume down cycles the lens.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { event.startTracking(); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            Tuning.nextIpd()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!event.isCanceled) renderer.requestRecenter()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { Tuning.nextLens(); true }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
        else windowManager.defaultDisplay.rotation

    private fun createSession(): Session {
        val s = Session(this)

        // 30 fps and a camera picture near 1280 px wide: sharp enough, and much cooler than the maximum.
        val filter = CameraConfigFilter(s).setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        s.getSupportedCameraConfigs(filter)
            .minByOrNull { abs(it.textureSize.width - 1280) }
            ?.let { s.setCameraConfig(it) }

        // Only tracking is needed: no planes, depth or light estimation.
        val config = Config(s)
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        config.setDepthMode(Config.DepthMode.DISABLED)
        config.setFocusMode(Config.FocusMode.AUTO)
        s.configure(config)
        return s
    }

    private fun fail(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private companion object {
        const val REQUEST_CAMERA = 1
    }
}
