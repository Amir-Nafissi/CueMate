package com.cuemate.core.model

data class RawFaceDetection(
    val normalizedCenterX: Float,
    val smileScore: Float,
    val surpriseScore: Float,
    val frownScore: Float,
    val confidence: Float
)

data class RawHandDetection(
    val normalizedCenterX: Float,
    val gestureLabel: String,
    val confidence: Float
)

data class InferenceResult(
    val faceDetections: List<RawFaceDetection>,
    val handDetections: List<RawHandDetection>
)
