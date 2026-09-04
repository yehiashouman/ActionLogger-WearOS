# Action Logger for Wear OS

Production-oriented action/session logger for Samsung Galaxy Watch and other Wear OS 3+ watches.

## What it records

During an active session only:
- Accelerometer
- Gyroscope
- Rotation vector when available

Every event retains the Android sensor hardware timestamp, callback elapsed time, reconstructed UTC wall time, accuracy status, sensor type, and raw values. No resampling is performed on-watch.

## Battery design

- Sensors are OFF outside an active recording.
- Balanced mode is 25 Hz and is the recommended default for wrist gesture ML.
- Battery mode: 15 Hz.
- High precision: 50 Hz.
- Sensor hardware batching: 2 seconds.
- No GPS, microphone, heart rate, Wi-Fi scanning, or network service.
- Recording auto-stop is configurable.
- Recording stops automatically at <=7% battery when not charging.
- A partial wake lock exists only while recording so screen-off recordings do not silently lose sensor data.

## Dataset layout

Internal app storage:

```
ActionLogger/
  sessions/
    smoking/
      20260904_225501_a1b2c3d4/
        metadata.json
        sensor_events.csv
        markers.csv
    drinking/
      ...
```

Export ZIP:

```
ActionLogger/
  DATASET_MANIFEST.json
  smoking/
    <session>/...
  drinking/
    <session>/...
```

## UI

- Create session
- Choose preset action or type a custom action
- Set subject ID and watch wrist
- Choose Battery / Balanced / High precision sampling
- Choose auto-stop duration
- Optional notes
- Start / Stop recording
- Add timestamped marker while recording
- List sessions
- View session details
- Delete session
- Export all sessions as one training dataset ZIP

## Android Studio

- Android Gradle Plugin: 8.13.2
- Kotlin: 2.2.21
- compile/target SDK: 36
- min SDK: 30 (Wear OS 3+)
- Java/JVM: 17
- Wear Compose Material 3: 1.6.2

Open the project root in Android Studio, allow Gradle sync, select the Wear OS watch, then Run.

## Before collecting a real dataset

Use one `subject_id` per person. Keep action sessions separate. Record negative/confusing actions too (for example drinking, eating, face touch and phone use if the target is smoking). Keep the watch wrist correctly labeled.
