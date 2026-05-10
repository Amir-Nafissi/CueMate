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
        val ar = image.width.toFloat() / image.height.toFloat()

        val faceResult = faceLandmarker.detectForVideo(image, timestampMs)
        val handResult = handLandmarker.detectForVideo(image, timestampMs)
        val gestureResult = gestureRecognizer.recognizeForVideo(image, timestampMs)

        val faceDetections = mutableListOf<RawFaceDetection>()
        val faceLandmarks = faceResult.faceLandmarks()
        for (landmarks in faceLandmarks) {
            if (landmarks.isEmpty()) continue
            val centerX = landmarks.mapNotNull { it.x() }.average().toFloat().coerceIn(0.0f, 1.0f)
            val smile = smileScore(landmarks, ar)
            val frown = frownScore(landmarks, ar)
            val surprise = surpriseScore(landmarks, ar)
            val confidence = faceConfidence(landmarks)
            val points = landmarks.mapNotNull { lm ->
                val x = lm.x()?.coerceIn(0.0f, 1.0f)
                val y = lm.y()?.coerceIn(0.0f, 1.0f)
                if (x == null || y == null) null else com.cuemate.core.model.NormalizedPoint(x, y)
            }
            faceDetections.add(
                RawFaceDetection(
                    normalizedCenterX = centerX,
                    smileScore = smile,
                    surpriseScore = surprise,
                    frownScore = frown,
                    confidence = confidence,
                    landmarks = points,
                )
            )
        }

        val handDetections = mutableListOf<RawHandDetection>()
        val gestureSets = gestureResult.gestures()
        val gestureLandmarks = gestureResult.landmarks()
        val fallbackHandLandmarks = handResult.landmarks()
        for (index in gestureSets.indices) {
            val gestureLabel = gestureSets[index].firstOrNull()?.categoryName().orEmpty()
            val confidence = gestureSets[index].firstOrNull()?.score() ?: 0f
            val landmarkList = gestureLandmarks.getOrNull(index).orEmpty().ifEmpty {
                fallbackHandLandmarks.getOrNull(index).orEmpty()
            }
            val centerX = handCenterX(landmarkList)
            val classification = classifyHandGesture(landmarkList, gestureLabel)
            val handPoints = landmarkList.mapNotNull { lm ->
                val x = lm.x()?.coerceIn(0.0f, 1.0f)
                val y = lm.y()?.coerceIn(0.0f, 1.0f)
                if (x == null || y == null) null else com.cuemate.core.model.NormalizedPoint(x, y)
            }
            handDetections.add(
                RawHandDetection(
                    normalizedCenterX = centerX,
                    gestureLabel = classification.label,
                    confidence = confidence.coerceAtLeast(classification.confidence),
                    landmarks = handPoints,
                )
            )
        }

        if (handDetections.isEmpty()) {
            for (index in fallbackHandLandmarks.indices) {
                val landmarks = fallbackHandLandmarks[index]
                val handedness = handResult.handedness().getOrNull(index)?.firstOrNull()?.categoryName().orEmpty()
                val centerX = handCenterX(landmarks)
                val classification = classifyHandGesture(landmarks, handedness)
                val handPoints = landmarks.mapNotNull { lm ->
                    val x = lm.x()?.coerceIn(0.0f, 1.0f)
                    val y = lm.y()?.coerceIn(0.0f, 1.0f)
                    if (x == null || y == null) null else com.cuemate.core.model.NormalizedPoint(x, y)
                }
                handDetections.add(
                    RawHandDetection(
                        normalizedCenterX = centerX,
                        gestureLabel = classification.label,
                        confidence = classification.confidence,
                        landmarks = handPoints,
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

    private fun normalizeGestureLabel(label: String): String {
        return label.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private data class HandClassification(
        val label: String,
        val confidence: Float,
    )

    private fun classifyHandGesture(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        fallbackLabel: String,
    ): HandClassification {
        if (landmarks.size < 21) {
            val normalizedFallback = normalizeGestureLabel(fallbackLabel)
            return HandClassification(
                label = normalizedFallback,
                confidence = if (normalizedFallback.isBlank() || normalizedFallback == "none") 0.20f else 0.40f,
            )
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

        fun distance(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
            val dx = a.x() - b.x()
            val dy = a.y() - b.y()
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        fun isExtended(tip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, pip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Boolean {
            return distance(tip, wrist) > distance(pip, wrist) * 1.12f
        }

        fun isVerticalThumb(tip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Boolean {
            val dx = kotlin.math.abs(tip.x() - wrist.x())
            val dy = kotlin.math.abs(tip.y() - wrist.y())
            return dy > dx * 1.15f
        }

        val indexExtended = isExtended(indexTip, indexPip)
        val middleExtended = isExtended(middleTip, middlePip)
        val ringExtended = isExtended(ringTip, ringPip)
        val pinkyExtended = isExtended(pinkyTip, pinkyPip)
        val extendedCount = listOf(indexExtended, middleExtended, ringExtended, pinkyExtended).count { it }

        val fingerTipSpread = distance(indexTip, pinkyTip)
        val fingerBaseSpread = distance(indexPip, pinkyPip)

        // Use thumb tip vs thumb MCP delta to determine thumb pose with tuned thresholds
        val dx = thumbTip.x() - thumbMcp.x()
        val dy = thumbTip.y() - thumbMcp.y()
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)

        // Tunable thresholds derived from device logs
        val THUMB_UP_HORIZONTAL_MIN = 0.08f
        val THUMB_DOWN_HORIZONTAL_MIN = 0.07f
        val THUMB_VERTICAL_MAX = 0.04f
        val THUMB_DOWN_DY_MIN = 0.03f
        val THUMB_DOWN_DY_MAX = 0.08f

        // Original vertical heuristic (kept as fallback)
        val verticalEnough = absDy > absDx * 1.25f && absDy > 0.02f

        val thumbCandidate = extendedCount <= 1

        // Primary heuristics tuned from device logcat:
        // - Thumbs up is the compact version of the pose.
        // - Thumbs down is the more open version of the same horizontal thumb offset.
        val thumbUpByHorizontal = dx >= 0.078f && dx <= 0.103f && absDy <= 0.022f &&
            fingerTipSpread >= 0.089f && fingerTipSpread <= 0.110f &&
            fingerBaseSpread >= 0.097f && fingerBaseSpread <= 0.116f &&
            extendedCount <= 1
        val thumbDownByHorizontal = dx <= -0.061f && dy >= 0.0f && dy <= 0.035f &&
            absDy <= 0.035f && fingerTipSpread >= 0.057f && fingerTipSpread <= 0.114f &&
            fingerBaseSpread >= 0.080f && fingerBaseSpread <= 0.114f &&
            extendedCount <= 1

        // Fallback vertical checks when the thumb is actually vertical in the image
        val thumbUpByVertical = thumbCandidate && verticalEnough && dy < -0.04f && absDx >= 0.04f
        val thumbDownByVertical = thumbCandidate && verticalEnough && dy > 0.03f && absDx >= 0.05f

        val thumbUpPose = thumbUpByHorizontal || thumbUpByVertical
        val thumbDownPose = thumbDownByHorizontal || thumbDownByVertical

        // wave / open palm: the logcat shows stable wave frames with all fingers extended
        // and a moderate spread band.
        val openPalmPose = extendedCount >= 4 && !thumbUpPose && !thumbDownPose &&
            fingerTipSpread >= 0.105f && fingerTipSpread <= 0.21f &&
            fingerBaseSpread >= 0.09f && fingerBaseSpread <= 0.16f &&
            absDy <= 0.08f

        // fist bump: compact closed hand with low spread and the thumb/hand staying close to the wrist.
        // Device logcat shows ext=0, spreadTip roughly 0.085-0.133, spreadBase roughly 0.104-0.161,
        // with a small horizontal delta and a larger upward thumb offset.
        val fistBumpPose = extendedCount <= 0 && !thumbUpPose && !thumbDownPose &&
            fingerTipSpread >= 0.085f && fingerTipSpread <= 0.133f &&
            fingerBaseSpread >= 0.104f && fingerBaseSpread <= 0.161f &&
            absDx <= 0.045f && dy >= 0.056f && dy <= 0.115f

        val debugLabel = when {
            thumbUpPose -> "thumb_up"
            thumbDownPose -> "thumb_down"
            openPalmPose -> "open_palm"
            fistBumpPose -> "fist_bump"
            else -> normalizeGestureLabel(fallbackLabel).ifBlank { "none" }
        }
        val debugConfidence = when (debugLabel) {
            "thumb_up", "thumb_down" -> 0.92f
            "open_palm" -> 0.88f
            "fist_bump" -> 0.89f
            else -> 0.25f
        }
        Log.d("MediaPipeInference", "classifyHandGesture: tip=(%.3f,%.3f) mcp=(%.3f,%.3f) dx=%.3f dy=%.3f absDx=%.3f absDy=%.3f vertical=%s ext=%d spreadTip=%.3f spreadBase=%.3f -> %s(%.2f)".format(thumbTip.x(), thumbTip.y(), thumbMcp.x(), thumbMcp.y(), dx, dy, absDx, absDy, verticalEnough, extendedCount, fingerTipSpread, fingerBaseSpread, debugLabel, debugConfidence))

        return when {
            thumbUpPose -> HandClassification(CueType.THUMBS_UP.name.lowercase(), 0.92f)
            thumbDownPose -> HandClassification(CueType.THUMBS_DOWN.name.lowercase(), 0.92f)
            openPalmPose -> HandClassification(CueType.WAVE.name.lowercase(), 0.88f)
            fistBumpPose -> HandClassification(CueType.FIST_BUMP.name.lowercase(), 0.89f)
            else -> {
                val normalizedFallback = normalizeGestureLabel(fallbackLabel)
                val confidence = when (normalizedFallback) {
                    "wave", "thumbup", "thumbsup", "thumbdown", "thumbsdown", "pointingup", "openpalm" -> 0.65f
                    else -> 0.25f
                }
                HandClassification(
                    label = when (normalizedFallback) {
                        "thumbup", "thumbsup" -> CueType.THUMBS_UP.name.lowercase()
                        "thumbdown", "thumbsdown" -> CueType.THUMBS_DOWN.name.lowercase()
                        "openpalm" -> CueType.WAVE.name.lowercase()
                        else -> normalizedFallback
                    },
                    confidence = confidence,
                )
            }
        }
    }

    private fun distance(
        a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
        b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
        ar: Float
    ): Float {
        if (a == null || b == null) return 0f
        val dx = (a.x() - b.x()) * ar
        val dy = a.y() - b.y()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun smileScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, ar: Float): Float {
        val leftMouth = landmarks.getOrNull(61)
        val rightMouth = landmarks.getOrNull(291)
        val leftEyeOuter = landmarks.getOrNull(33)
        val rightEyeOuter = landmarks.getOrNull(263)
        
        if (leftMouth == null || rightMouth == null || leftEyeOuter == null || rightEyeOuter == null) return 0f
        
        val mouthWidth = distance(leftMouth, rightMouth, ar)
        val faceWidth = distance(leftEyeOuter, rightEyeOuter, ar).coerceAtLeast(0.001f)
        
        val relativeWidth = mouthWidth / faceWidth
        val score = ((relativeWidth - 0.58f) / 0.12f).coerceIn(0.0f, 1.0f)
        Log.d("MediaPipeInference", "Smile Score: $score (relWidth=$relativeWidth, mouthWidth=$mouthWidth, faceWidth=$faceWidth)")
        return score
    }

    private fun surpriseScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, ar: Float): Float {
        val upperLip = landmarks.getOrNull(13)
        val lowerLip = landmarks.getOrNull(14)
        val topFace = landmarks.getOrNull(10)
        val bottomFace = landmarks.getOrNull(152)
        val leftEyeUpper = landmarks.getOrNull(159)
        val leftEyeLower = landmarks.getOrNull(145)

        if (upperLip == null || lowerLip == null || topFace == null || bottomFace == null || leftEyeUpper == null || leftEyeLower == null) return 0f

        val mouthOpen = distance(upperLip, lowerLip, ar)
        val leftEyeOpen = distance(leftEyeUpper, leftEyeLower, ar)
        val faceHeight = distance(topFace, bottomFace, ar).coerceAtLeast(0.001f)

        val relativeMouthOpen = mouthOpen / faceHeight
        val relativeEyeOpen = leftEyeOpen / faceHeight

        val mouthScore = ((relativeMouthOpen - 0.03f) / 0.05f).coerceIn(0.0f, 1.0f)
        val eyeScore = ((relativeEyeOpen - 0.035f) / 0.02f).coerceIn(0.0f, 1.0f)

        val score = (mouthScore * 0.7f + eyeScore * 0.3f)
        Log.d("MediaPipeInference", "Surprise Score: $score (relMouth=$relativeMouthOpen, relEye=$relativeEyeOpen)")
        return score
    }

    private fun frownScore(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, ar: Float): Float {
        val leftMouth = landmarks.getOrNull(61)
        val rightMouth = landmarks.getOrNull(291)
        val centerMouth = landmarks.getOrNull(13)
        val topFace = landmarks.getOrNull(10)
        val bottomFace = landmarks.getOrNull(152)

        if (leftMouth == null || rightMouth == null || centerMouth == null || topFace == null || bottomFace == null) return 0f

        val faceHeight = distance(topFace, bottomFace, ar).coerceAtLeast(0.001f)

        val avgCornerY = (leftMouth.y() + rightMouth.y()) / 2.0f
        val dropY = avgCornerY - centerMouth.y()
        val relativeDrop = dropY / faceHeight

        val score = ((relativeDrop - 0.015f) / 0.02f).coerceIn(0.0f, 1.0f)
        Log.d("MediaPipeInference", "Frown Score: $score (relDrop=$relativeDrop)")
        return score
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
