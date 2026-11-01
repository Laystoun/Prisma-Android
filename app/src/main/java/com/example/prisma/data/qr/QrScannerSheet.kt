package com.example.prisma

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Шторка со сканером QR-кода.
 * При успешном сканировании публичного ключа отправляет результат через Fragment Result API
 * (REQUEST_KEY / RESULT_KEY) и закрывается.
 * Разрешение на камеру должно быть выдано ДО показа этой шторки.
 */
class QrScannerSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "QrScannerSheet"
        const val REQUEST_KEY = "qr_scanner_result"
        const val RESULT_KEY = "public_key"
        private const val ERROR_SHOW_MS = 2000L
    }

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private lateinit var previewView: PreviewView
    private lateinit var tvError: TextView
    private lateinit var torchCard: MaterialCardView

    private val hideError = Runnable { tvError.visibility = View.GONE }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) decodeFromGallery(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_qr_scanner, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.pv_qrScanner)
        tvError = view.findViewById(R.id.tv_qrScanner_error)
        torchCard = view.findViewById(R.id.cv_qrScanner_torch)

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.cl_qrScanner_overlay)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        view.findViewById<View>(R.id.cv_qrScanner_close).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btn_qrScanner_gallery).setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        startCamera()
    }

    override fun onStart() {
        super.onStart()

        // Растягиваем шторку на весь экран и отключаем перетаскивание,
        // чтобы свайпы не мешали камере. Закрывается стрелкой или кнопкой "Назад".
        val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = bottomSheetDialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        sheet.setBackgroundColor(Color.TRANSPARENT)

        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            if (view == null) return@addListener

            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(imageProxy) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                setupTorch()
            } catch (e: Exception) {
                showTemporaryError("Не удалось запустить камеру")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || handled.get()) {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    codes.firstNotNullOfOrNull { it.rawValue }?.let { onQrText(it) }
                }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    private fun onQrText(raw: String) {
        val key = parsePublicKey(raw)
        if (key == null) {
            showTemporaryError("Это не QR-код публичного ключа Prisma")
            return
        }

        // Защита от повторной отправки, пока шторка закрывается
        if (!handled.compareAndSet(false, true)) return

        val result = Bundle().apply { putString(RESULT_KEY, key) }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    private fun decodeFromGallery(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(requireContext(), uri)
        } catch (e: IOException) {
            showTemporaryError("Не удалось открыть изображение")
            return
        }

        scanner.process(image)
            .addOnSuccessListener { codes ->
                val raw = codes.firstNotNullOfOrNull { it.rawValue }
                if (raw == null) {
                    showTemporaryError("QR-код на изображении не найден")
                } else {
                    onQrText(raw)
                }
            }
            .addOnFailureListener {
                showTemporaryError("Не удалось распознать изображение")
            }
    }

    private fun setupTorch() {
        val cam = camera ?: return

        if (!cam.cameraInfo.hasFlashUnit()) {
            torchCard.visibility = View.GONE
            return
        }

        val offColor = Color.argb(128, 42, 42, 46)
        val onColor = ContextCompat.getColor(requireContext(), R.color.primary)

        torchCard.setOnClickListener {
            val enable = cam.cameraInfo.torchState.value != TorchState.ON
            cam.cameraControl.enableTorch(enable)
        }

        cam.cameraInfo.torchState.observe(viewLifecycleOwner) { state ->
            torchCard.setCardBackgroundColor(if (state == TorchState.ON) onColor else offColor)
        }
    }

    private fun showTemporaryError(message: String) {
        if (view == null) return
        tvError.text = message
        tvError.visibility = View.VISIBLE
        tvError.removeCallbacks(hideError)
        tvError.postDelayed(hideError, ERROR_SHOW_MS)
    }

    override fun onDestroyView() {
        if (::tvError.isInitialized) tvError.removeCallbacks(hideError)
        cameraProvider?.unbindAll()
        camera = null
        cameraProvider = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        scanner.close()
        super.onDestroy()
    }
}