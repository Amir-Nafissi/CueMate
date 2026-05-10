    package com.cuemate.camera

    import android.os.SystemClock
    import androidx.camera.core.CameraSelector
    import androidx.camera.core.ImageAnalysis
    import androidx.camera.core.ImageProxy
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.lifecycle.LifecycleOwner
    import com.google.mediapipe.framework.image.MediaImageBuilder
    import com.google.mediapipe.framework.image.MPImage
    import kotlinx.coroutines.CoroutineDispatcher
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.channels.BufferOverflow
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.asSharedFlow
    import kotlinx.coroutines.withContext
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors

    class CameraStreamProvider(
        private val context: android.content.Context,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        private val frameFlow = MutableSharedFlow<MPImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        private var lastFrameTimestampMs = 0L
        private var cameraProvider: ProcessCameraProvider? = null
        private var imageAnalysis: ImageAnalysis? = null

        fun frames(): Flow<MPImage> = frameFlow.asSharedFlow()

        suspend fun start(lifecycleOwner: LifecycleOwner) = withContext(dispatcher) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            imageAnalysis = analysis
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis
            )
        }

        suspend fun stop() = withContext(dispatcher) {
            cameraProvider?.unbindAll()
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
        }

        fun close() {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdownNow()
        }

        private fun processImageProxy(imageProxy: ImageProxy) {
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameTimestampMs < (1000L / 12L)) {
                    return
                }
                lastFrameTimestampMs = now
                val mediaImage = imageProxy.image ?: return
                val mpImage = MediaImageBuilder(mediaImage).build()
                frameFlow.tryEmit(mpImage)
            } finally {
                imageProxy.close()
            }
        }
    }
