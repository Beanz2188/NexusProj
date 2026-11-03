package com.nexuslights.panel.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import kotlin.math.abs

/**
 * Presence detection from the front camera, without ever storing or sending
 * an image: frames are reduced to a 24x18 grid of average brightness on the
 * device and compared with the previous grid. Runs at a few fps from the
 * smallest YUV size the camera offers, to keep CPU and power low.
 *
 * A global brightness shift (a light turning on, auto-exposure settling) moves
 * every cell the same way and is subtracted out, so changing the lights
 * doesn't count as "someone walked in".
 */
class CameraMotionDetector(
    private val ctx: Context,
    private val onMotion: () -> Unit,
) {
    @Volatile var sensitivity = 3
    @Volatile var running = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private var prev: FloatArray? = null
    private var lastProcessed = 0L
    private var startedAt = 0L
    private var hits = 0

    fun start() {
        if (running) return
        running = true
        val t = HandlerThread("camera-motion").apply { start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post { open() }
    }

    fun stop() {
        if (!running) return
        running = false
        val h = handler
        h?.post {
            closeAll()
            thread?.quitSafely()
        }
        handler = null
        thread = null
    }

    private fun closeAll() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        prev = null
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!running) return
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "No camera", e); null
        } ?: return
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width >= 160 && it.height >= 120 }
            ?.minByOrNull { it.width * it.height } ?: return
        val fps: Range<Int>? = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.minByOrNull { it.upper * 100 + it.lower }

        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try { process(img) } finally { img.close() }
        }, handler)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (!running) { cam.close(); return }
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (!running) { s.close(); return }
                            session = s
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(r.surface)
                                fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }
                            startedAt = SystemClock.elapsedRealtime()
                            runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                                .onFailure { Log.w(TAG, "repeating request failed", it); retryLater() }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "configure failed"); retryLater() }
                    }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null; retryLater() }
                override fun onError(cam: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error"); cam.close(); device = null; retryLater()
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "openCamera failed", e); retryLater()
        }
    }

    private fun retryLater() {
        closeAll()
        handler?.postDelayed({ if (running) open() }, 10_000)
    }

    private fun process(img: Image) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessed < 250) return
        lastProcessed = now
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = img.width
        val h = img.height
        val cells = FloatArray(GX * GY)
        val cw = w / GX
        val ch = h / GY
        for (gy in 0 until GY) for (gx in 0 until GX) {
            var sum = 0; var n = 0
            var y = gy * ch
            while (y < (gy + 1) * ch) {
                var x = gx * cw
                val row = y * rowStride
                while (x < (gx + 1) * cw) {
                    sum += buf.get(row + x * pixStride).toInt() and 0xFF
                    n++
                    x += 4
                }
                y += 4
            }
            cells[gy * GX + gx] = if (n > 0) sum.toFloat() / n else 0f
        }
        val p = prev
        prev = cells
        // Let auto-exposure settle after (re)opening.
        if (p == null || now - startedAt < 1500) return

        var meanDiff = 0f
        for (i in cells.indices) meanDiff += cells[i] - p[i]
        meanDiff /= cells.size

        val s = sensitivity.coerceIn(1, 5) - 1
        val cellThreshold = CELL_THRESHOLDS[s]
        var changed = 0
        for (i in cells.indices) if (abs(cells[i] - p[i] - meanDiff) > cellThreshold) changed++
        val fraction = changed.toFloat() / cells.size

        if (fraction > AREA_THRESHOLDS[s]) {
            // two consecutive frames in a row to ignore sensor noise
            if (++hits >= 2) { hits = 0; onMotion() }
        } else {
            hits = 0
        }
    }

    companion object {
        private const val TAG = "CamMotion"
        private const val GX = 24
        private const val GY = 18
        private val CELL_THRESHOLDS = floatArrayOf(22f, 16f, 12f, 9f, 7f)
        private val AREA_THRESHOLDS = floatArrayOf(0.12f, 0.08f, 0.05f, 0.035f, 0.02f)
    }
}
