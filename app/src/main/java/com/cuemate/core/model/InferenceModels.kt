package com.cuemate.core.model

data class RawFaceDetection(
    val normalizedCenterX: Float,
    val smileScore: Float,
    val surpriseScore: Float,
    val frownScore: Float,
    val confidence: Float,
    val landmarks: List<NormalizedPoint>
)

data class RawHandDetection(
    val normalizedCenterX: Float,
    val gestureLabel: String,
    val confidence: Float,
    val landmarks: List<NormalizedPoint>
)

data class InferenceResult(
    val faceDetections: List<RawFaceDetection>,
    val handDetections: List<RawHandDetection>
)

data class NormalizedPoint(
    val x: Float,
    val y: Float
)
