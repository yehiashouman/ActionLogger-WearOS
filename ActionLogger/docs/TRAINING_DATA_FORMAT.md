# Training data format

## `metadata.json`

One file per session. Important fields:

- `schema_version`: currently 2
- `session_id`: globally unique-ish timestamp + UUID suffix
- `subject_id`: stable ID for the participant
- `action_label`: human-readable training class
- `action_slug`: filesystem-safe class name
- `wrist`: left/right
- `sampling_mode`: battery/balanced/high_precision
- `requested_sample_rate_hz`: requested Android sensor period
- `sensor_batch_latency_ms`: requested batching latency
- `started_wall_time_ms`, `started_elapsed_realtime_ns`: clock anchors
- `sensors`: exact sensor hardware descriptors
- `event_count`, `events_by_sensor`: recorded-event totals
- `battery_start_pct`, `battery_end_pct`: session battery impact metadata
- `stop_reason`: user, auto_stop, critical_battery, service_destroyed or process_interrupted

## `sensor_events.csv`

Long-form raw event table:

```
sequence,sensor_timestamp_ns,callback_elapsed_realtime_ns,wall_time_utc_ms,sensor_type,string_type,accuracy,v0,v1,v2,v3,v4
```

### Timing

Use `sensor_timestamp_ns` for model windowing and synchronization. It is monotonic and originates from Android's sensor event timing. Do not use callback arrival time to resample the signal.

`callback_elapsed_realtime_ns` is retained to quantify delivery/batching latency.

`wall_time_utc_ms` is reconstructed from the session's monotonic/wall-clock anchors and is intended for human correlation, not signal timing.

### Values

- Accelerometer: v0=x, v1=y, v2=z in m/s^2
- Gyroscope: v0=x, v1=y, v2=z in rad/s
- Rotation vector: Android Sensor.TYPE_ROTATION_VECTOR values; number of populated values depends on device implementation

Unused value columns are empty.

## `markers.csv`

```
elapsed_realtime_ns,wall_time_utc_ms,marker,note
```

Automatically includes `session_start` and `session_stop`. The UI can add `manual_marker` entries during recording.

## Recommended preprocessing

1. Load sessions by action folder.
2. Split train/validation/test by `subject_id` or complete session; never randomly split individual windows from the same session across train and validation.
3. Partition events by sensor type.
4. Resample using `sensor_timestamp_ns` to a common frequency if your model requires aligned tensors.
5. Use 3-8 second windows with overlap depending on the action.
6. Preserve action label, subject and wrist as metadata.
7. Report precision, recall, F1 and false positives per hour/session for event detectors.
