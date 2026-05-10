# CueMate

**CueMate** is an Android assistive application designed to restore non-verbal social awareness for blind and visually impaired individuals. By leveraging real-time, on-device computer vision, the app interprets the facial expressions and gestures of people in their surroundings, transforming invisible social cues into actionable auditory and tactile feedback.

## Features

- **Facial Expression Detection:** Identifies expressions like *Smiling*, *Surprise*, and *Neutral* using robust facial landmark analysis that works across diverse distances and angles.
- **Gesture Recognition:** Detects common social and communicative gestures, including *Waving*, *Pointing*, *Thumbs Up/Down*, *Handshake Reach*, and *Fist Bump*.
- **Directional Awareness:** Provides context on the location of the detected cue (Left, Ahead, Right) relative to the user.
- **Multimodal Feedback:**
  - **Text-to-Speech (TTS):** Spoken announcements of detected social cues.
  - **Directional Haptics:** Distinct vibration patterns to quickly convey specific actions (e.g., reaching for a handshake) without relying solely on audio.
- **Privacy-First & Offline:** The entire AI pipeline runs locally on-device using Google’s MediaPipe framework. There is zero cloud dependency, and no camera frames are ever saved or transmitted.
- **Accessible Design:** Fully TalkBack-compliant UI with voice-controlled navigation built specifically for visually impaired users.

## Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Camera integration:** CameraX
- **Computer Vision:** Google MediaPipe Tasks Vision API (Face Landmarker & Gesture Recognizer)
- **Concurrency & State Management:** Kotlin Coroutines & StateFlow

## How It Works

1. **Camera Feed:** CameraX captures live frames optimized for fast processing.
2. **Inference Engine:** `MediaPipeInferenceEngine` runs the frames through the MediaPipe Face Landmarker and Gesture Recognizer models. Custom geometric algorithms determine the severity of expressions (like smile width vs. eye distance) to create scale-invariant detection scores.
3. **Fusion Logic:** `CueFusionEngine` aggregates the raw inference scores, applying debounce stability filters to prevent noisy, rapidly flickering detections.
4. **Feedback:** `AccessibilityFeedbackManager` translates the stable social cues into speech and localized haptics, alerting the user to what is happening around them.

## Building and Running

1. Clone the repository and open it in **Android Studio**.
2. Ensure you have the Android SDK 34 installed.
3. Allow Gradle to sync the dependencies (including MediaPipe Tasks Vision).
4. Connect an Android device (API 28 or higher).
5. Build and run the `app` module.

*Note: For the application to function, it requires Camera and Microphone (for voice commands) permissions upon first launch.*
