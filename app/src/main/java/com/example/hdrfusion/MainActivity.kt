package com.example.hdrfusion

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    private lateinit var editSteps: EditText
    private lateinit var editStops: EditText
    private lateinit var editIso: EditText
    private lateinit var editIsoWeight: EditText
    private lateinit var editFocal: EditText
    private lateinit var checkOptimizeSaturation: CheckBox
    private lateinit var checkBurstStacking: CheckBox

    private var controller: CameraBracketController? = null
    private val scope = MainScope()

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setupCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        editSteps = findViewById(R.id.editSteps)
        editStops = findViewById(R.id.editStops)
        editIso = findViewById(R.id.editIso)
        editIsoWeight = findViewById(R.id.editIsoWeight)
        editFocal = findViewById(R.id.editFocal)
        checkOptimizeSaturation = findViewById(R.id.checkOptimizeSaturation)
        checkBurstStacking = findViewById(R.id.checkBurstStacking)

        captureButton.setOnClickListener { runBracketAndFuse() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupCamera() {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val ctrl = CameraBracketController(this, mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.first())
        ctrl.start()
        controller = ctrl

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                statusText.text = "Camera ready"
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun runBracketAndFuse() {
        val ctrl = controller ?: return
        val texture = textureView.surfaceTexture ?: run {
            Toast.makeText(this, "Preview not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val steps = editSteps.text.toString().toIntOrNull()?.coerceIn(2, 9) ?: 5
        val stops = editStops.text.toString().toFloatOrNull() ?: 1.0f
        val baseIso = editIso.text.toString().toIntOrNull() ?: 100
        val isoWeight = editIsoWeight.text.toString().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        val focal = editFocal.text.toString().toFloatOrNull() // fx, null = auto/first available

        val config = BracketConfig(
            steps = steps,
            stopsPerStep = stops,
            baseIso = baseIso,
            isoWeight = isoWeight,
            focalLengthMm = focal,
            optimizeForSaturation = checkOptimizeSaturation.isChecked,
            useBurstStacking = checkBurstStacking.isChecked
        )

        captureButton.isEnabled = false
        statusText.text = "Capturing 0/${config.steps}..."

        // Live preview stream stays modest for smooth rendering; the still-capture
        // resolution (JPEG or RAW) is chosen independently, from the sensor's own max
        // output size, inside CameraBracketController.
        val previewSize = Size(1920, 1080)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        scope.launch {
            try {
                val frames = ctrl.captureBracket(
                    previewSurface, config,
                    onProgress = { done, total ->
                        runOnUiThread { statusText.text = "Capturing $done/$total..." }
                    },
                    onStatus = { message ->
                        runOnUiThread { statusText.text = message }
                    }
                )
                runOnUiThread { statusText.text = "Fusing (argmax saturation)..." }
                val fused = SaturationFusion.fuse(frames)
                val uri = saveToGallery(fused)
                runOnUiThread {
                    statusText.text = if (uri != null) "Saved fused image to gallery" else "Fusion done, save failed"
                    captureButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    captureButton.isEnabled = true
                }
            }
        }
    }

    private fun saveToGallery(bitmap: android.graphics.Bitmap): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "hdrfusion_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HDRFusion")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }
        return uri
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.stop()
    }
}
