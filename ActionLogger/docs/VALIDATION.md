# Validation notes

Validated in the supplied project package:

- Required project files exist.
- AndroidManifest XML parses successfully.
- Kotlin/Gradle/XML files pass delimiter-balance/static checks.
- No hardcoded filesystem path is used for watch storage.
- Session storage uses action folders.
- Every session contains metadata + raw sensor CSV + marker CSV.
- Sensor timestamps are retained without on-watch resampling.
- Accelerometer and gyroscope are required before recording starts.
- Rotation vector is recorded when available.
- Sensors are registered only between Start and Stop.
- Hardware sensor batching is enabled.
- Foreground service is used for active screen-off recording.
- Timed wake lock is released at stop/destroy.
- Critical-battery and auto-stop guards are implemented.
- Interrupted recordings are finalized with an explicit stop reason on next service creation.
- Dataset export retains action-folder hierarchy.

This environment does not contain an Android SDK/Gradle installation, so APK compilation and on-device instrumentation could not be executed here. Open the project in Android Studio and run the normal Gradle build/device tests before Play Store or enterprise distribution.
