#!/usr/bin/env python3
import csv
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
errors = []
sessions = 0

for metadata in root.rglob("metadata.json"):
    sessions += 1
    session = metadata.parent
    try:
        data = json.loads(metadata.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{metadata}: invalid JSON: {exc}")
        continue
    for key in ("session_id", "subject_id", "action_label", "action_slug", "wrist", "started_utc"):
        if not data.get(key):
            errors.append(f"{metadata}: missing {key}")
    events = session / "sensor_events.csv"
    markers = session / "markers.csv"
    if not events.exists(): errors.append(f"{session}: missing sensor_events.csv")
    if not markers.exists(): errors.append(f"{session}: missing markers.csv")
    if events.exists():
        with events.open(newline="", encoding="utf-8") as fh:
            reader = csv.reader(fh)
            header = next(reader, [])
            expected = ["sequence","sensor_timestamp_ns","callback_elapsed_realtime_ns","wall_time_utc_ms","sensor_type","string_type","accuracy","v0","v1","v2","v3","v4"]
            if header != expected:
                errors.append(f"{events}: unexpected CSV header")

print(f"sessions={sessions}")
if errors:
    for error in errors: print("ERROR:", error)
    sys.exit(1)
print("dataset validation: OK")
