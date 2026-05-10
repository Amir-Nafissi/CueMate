package com.cuemate.inference

import com.cuemate.core.model.Direction
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.cuemate.core.model.InferenceResult
import com.cuemate.core.model.RawFaceDetection
import com.cuemate.core.model.RawHandDetection
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import com.cuemate.core.model.CueType

class MediaPipeInferenceEngine(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val lastTimestampMs = AtomicLong(0L)
    @Volatile
    private var closed = false

    private val faceLandmarker: FaceLandmarker
    private val handLandmarker: HandLandmarker
    private val gestureRecognizer: GestureRecognizer

    init {
        val face = createFaceLandmarker()
        val hand = createHandLandmarker()
        val gesture = createGestureRecognizer()
        faceLandmarker = face
        handLandmarker = hand
        gestureRecognizer = gesture
    }

    suspend fun analyze(image: MPImage): InferenceResult = withContext(dispatcher) {
        check(!closed) { "Inference engine is closed" }
        val timestampMs = nextTimestampMs()

        val faceResult = faceLandmarker.detectForVideo(image, timestampMs)
        val handResult = handLandmarker.detectForVideo(image, timestampMs)
        val gestureResult = gestureRecognizer.recognizeForVideo(image, timestampMs)

        val faceDetections = faceResult.faceLandmarks().map { landmarks ->
            val faceDirectionX = landmarks.firstOrNull()?.x()?.coerceIn(0.0f, 1.0f) ?: 0.5f
            RawFaceDetection(
                normalizedCenterX = faceDirectionX,
                smileScore = smileScore(landmarks),
                surpriseScore = surpriseScore(landmarks),
                frownScore = frownScore(landmarks),
                confidence = faceConfidence(landmarks),
            )
        }

        val handDetections = mutableListOf<RawHandDetection>()
        val gestureSets = gestureResult.gestures()
        val handLandmarks = gestureResult.landmarks()
        for (index in gestureSets.indices) {
            val gestureLabel = gestureSets[index].firstOrNull()?.categoryName().orEmpty()
            val confidence = gestureSets[index].firstOrNull()?.score() ?: 0f
            val landmarkList = handLandmarks.getOrNull(index).orEmpty()
            val centerX = handCenterX(landmarkList)
            val selectedLabel = when (normalizeGestureLabel(gestureLabel)) {
                "openpalm" -> CueType.WAVE.name.lowercase()
                "pointingup" -> CueType.POINT.name.lowercase()
                "thumbup", "thumbsup" -> CueType.THUMBS_UP.name.lowercase()
                "wave" -> CueType.WAVE.name.lowercase()
                else -> heuristicGestureLabel(landmarkList, gestureLabel)
            }
            handDetections.add(
                RawHandDetection(
                    normalizedCenterX = centerX,
                    gestureLabel = selectedLabel,
                    confidence = confidence.coerceAtLeast(handConfidenceFromLandmarks(landmarkList)),
                )
            )
        }

        if (handDetections.isEmpty()) {
            for (index in handResult.landmarks().indices) {
                val landmarks = handResult.landmarks()[index]
                val handedness = handResult.handedness().getOrNull(index)?.firstOrNull()?.categoryName().orEmpty()
                val centerX = handCenterX(landmarks)
                handDetections.add(
                    RawHandDetection(
                        normalizedCenterX = centerX,
                        gestureLabel = heuristicGestureLabel(landmarks, handedness),
                        confidence = handConfidenceFromLandmarks(landmarks),
                    )
                )
            }
        }

        InferenceResult(
            faceDetections = faceDetections,
            handDetections = handDetections,
        )
    }

    override fun close() {
        closed = true
        faceLandmarker.close()
        handLandmarker.close()
        gestureRecognizer.close()
    }

    private fun createFaceLandmarker(): FaceLandmarker {
        return FaceLandmarker.createFromOptions(context, faceOptions())
    }

    private fun createHandLandmarker(): HandLandmarker {
        return HandLandmarker.createFromOptions(context, handOptions())
    }

    private fun createGestureRecognizer(): GestureRecognizer {
        return GestureRecognizer.createFromOptions(context, gestureOptions())
    }


    private fun faceOptions(): FaceLandmarker.FaceLandmarkerOptions {
        return FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(ModelAssets.FACE_LANDMARKER)
                    .build(),
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build()
    }

    private fun handOptions(): HandLandmarker.HandLandmarkerOptions {
        return HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(ModelAssets.HAND_LANDMARKER)
                    .build(),
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    }

    private fun gestureOptions(): GestureRecognizer.GestureRecognizerOptions {
        return GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(ModelAssets.GESTURE_RECOGNIZER)
                    .build(),
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    }

    private fun nextTimestampMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val last = lastTimestampMs.get()
        val next = if (now > last) now else last + 1L
        lastTimestampMs.set(next)
        return next
    }

    private fun handCenterX(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        if (landmarks.isEmpty()) return 0.5f
        val sampleIndices = listOf(0, 5, 9, 13, 17)
        val xs = sampleIndices.mapNotNull { landmarks.getOrNull(it)?.x() }
        return if (xs.isEmpty()) 0.5f else xs.average().toFloat().coerceIn(0.0f, 1.0f)
    }

    private fun handConfidenceFromLandmarks(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        return if (landmarks.isEmpty()) 0f else 0.75f
    }

    private fun normalizeGestureLabel(label: String): String {
        return label.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun heuristicGestureLabel(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        fallbackLabel: String,
    ): String {
        if (landmarks.size < 21) {
            return normalizeGestureLabel(fallbackLabel)
        }

        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val middleTip = landmarks[12]
        val ringTip = landmarks[16]
        val pinkyTip = landmarks[20]
        val thumbMcp = landmarks[2]
        val indexPip = landmarks[6]
        val middlePip = landmarks[10]
        val ringPip = landmarks[14]
        val pinkyPip = landmarks[18]

        val thumbUp = thumbTip.y() < wrist.y() - 0.08f && thumbTip.y() < thumbMcp.y() - 0.03f
        val fingersExtended = listOf(
            indexTip.y() < indexPip.y() - 0.03f,
            middleTip.y() < middlePip.y() - 0.03f,
            ringTip.y() < ringPip.y() - 0.03f,
            pinkyTip.y() < pinkyPip.y() - 0.03f,
        ).count { it }
        val openPalm = fingersExtended >= 3

        return when {
            thumbUp && fingersExtended <= 1 -> CueType.THUMBS_UP.name.lowercase()
            openPalm -> CueType.WAVE.name.lowercase()
            else -> normalizeGestureLabel(fallbackLabel)
        }
    }

    private fun smileScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        val leftMouth = landmarks.getOrNull(61)
        val rightMouth = landmarks.getOrNull(291)
        val upperLip = landmarks.getOrNull(13)
        val lowerLip = landmarks.getOrNull(14)
        if (leftMouth == null || rightMouth == null || upperLip == null || lowerLip == null) return 0f
        val mouthWidth = (rightMouth.x() - leftMouth.x()).let { kotlin.math.abs(it) }
        val mouthOpen = (lowerLip.y() - upperLip.y()).let { kotlin.math.abs(it) }
        val score = ((mouthWidth * 1.2f) + (mouthOpen * 0.8f)).coerceIn(0.0f, 1.0f)
        Log.d("MediaPipeInference", "Smile Score: $score (width=$mouthWidth, open=$mouthOpen)")
        return score
    }

    private fun surpriseScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        val upperLip = landmarks.getOrNull(13)
        val lowerLip = landmarks.getOrNull(14)
        val leftEyeUpper = landmarks.getOrNull(159)
        val leftEyeLower = landmarks.getOrNull(145)
        if (upperLip == null || lowerLip == null || leftEyeUpper == null || leftEyeLower == null) return 0f
        val mouthOpen = kotlin.math.abs(lowerLip.y() - upperLip.y())
        val eyeOpen = kotlin.math.abs(leftEyeLower.y() - leftEyeUpper.y())
        return (mouthOpen * 1.5f + eyeOpen * 0.5f).coerceIn(0.0f, 1.0f)
    }

    private fun frownScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        val leftMouth = landmarks.getOrNull(61)
        val rightMouth = landmarks.getOrNull(291)
        val upperLip = landmarks.getOrNull(13)
        val lowerLip = landmarks.getOrNull(14)
        if (leftMouth == null || rightMouth == null || upperLip == null || lowerLip == null) return 0f
        val mouthWidth = kotlin.math.abs(rightMouth.x() - leftMouth.x())
        val lipGap = kotlin.math.abs(lowerLip.y() - upperLip.y())
        return (1.0f - mouthWidth + (0.2f - lipGap).coerceAtLeast(0f)).coerceIn(0.0f, 1.0f)
    }

    private fun faceConfidence(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        return if (landmarks.isEmpty()) 0f else 0.8f
    }

    private fun directionFromX(centerX: Float): Direction {
        return when {
            centerX <= 0.33f -> Direction.LEFT
            centerX <= 0.66f -> Direction.CENTER
            else -> Direction.RIGHT
        }
    }
}
