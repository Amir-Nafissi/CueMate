    package com.cuemate.camera

    import android.graphics.Bitmap
    import android.os.SystemClock
    import androidx.camera.core.CameraSelector
    import androidx.camera.core.ImageAnalysis
    import androidx.camera.core.ImageProxy
    import androidx.camera.core.Preview
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.camera.view.PreviewView
    import androidx.lifecycle.LifecycleOwner
    import com.google.mediapipe.framework.image.BitmapImageBuilder
    import com.google.mediapipe.framework.image.MPImage
    import kotlinx.coroutines.CoroutineDispatcher
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.channels.BufferOverflow
    import kotlinx.coroutines.flow.asSharedFlow
    import kotlinx.coroutines.withContext
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors

data class CameraFrame(
    val image: MPImage,
    private val imageProxy: ImageProxy,
) {
    fun close() {
        imageProxy.close()
    }
}

class CameraStreamProvider(
        private val context: android.content.Context
    ) {
        private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        private val frameFlow = MutableSharedFlow<CameraFrame>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private var lastFrameTimestampMs = 0L
        private var cameraProvider: ProcessCameraProvider? = null
        private var imageAnalysis: ImageAnalysis? = null
        private var preview: Preview? = null
        var previewView: PreviewView? = null

        fun frames(): Flow<CameraFrame> = frameFlow.asSharedFlow()

        suspend fun start(lifecycleOwner: LifecycleOwner) = withContext(Dispatchers.Main) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            imageAnalysis = analysis
            
            // Create and bind preview if PreviewView is available
            val previewUseCase = Preview.Builder().build()
            preview = previewUseCase
            previewView?.let { view ->
                previewUseCase.setSurfaceProvider(view.surfaceProvider)
            }
            
            provider.unbindAll()
            
            // Bind preview and analysis to lifecycle
            if (previewView != null) {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    analysis
                )
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            }
        }

        suspend fun stop() = withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            preview = null
        }

        fun close() {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdownNow()
        }

        private fun processImageProxy(imageProxy: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameTimestampMs < (1000L / 12L)) {
                imageProxy.close()
                return
            }
            lastFrameTimestampMs = now
            val plane = imageProxy.planes.firstOrNull() ?: run {
                imageProxy.close()
                return
            }
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val mpImage = BitmapImageBuilder(bitmap).build()
            if (!frameFlow.tryEmit(CameraFrame(mpImage, imageProxy))) {
                android.util.Log.d("CameraStreamProvider", "Dropping frame because buffer is full")
                imageProxy.close()
            }
        }
    }
