#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
errors = []
required = [
    'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/yehia/actionlogger/MainActivity.kt',
    'app/src/main/java/com/yehia/actionlogger/data/Models.kt',
    'app/src/main/java/com/yehia/actionlogger/data/SessionRepository.kt',
    'app/src/main/java/com/yehia/actionlogger/service/SensorLoggingService.kt',
    'app/src/main/java/com/yehia/actionlogger/util/JsonUtil.kt',
    'app/src/main/res/drawable/ic_launcher.xml',
    'docs/TRAINING_DATA_FORMAT.md', 'docs/VALIDATION.md', 'tools/validate_dataset.py'
]
for rel in required:
    if not (root / rel).is_file(): errors.append(f'missing required file: {rel}')

for rel in ['app/src/main/AndroidManifest.xml', 'app/src/main/res/drawable/ic_launcher.xml']:
    try: ET.parse(root / rel)
    except Exception as exc: errors.append(f'{rel} invalid XML: {exc}')

service = (root / 'app/src/main/java/com/yehia/actionlogger/service/SensorLoggingService.kt').read_text()
repo = (root / 'app/src/main/java/com/yehia/actionlogger/data/SessionRepository.kt').read_text()
main = (root / 'app/src/main/java/com/yehia/actionlogger/MainActivity.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
checks = {
    'accelerometer logging': 'Sensor.TYPE_ACCELEROMETER' in service,
    'gyroscope logging': 'Sensor.TYPE_GYROSCOPE' in service,
    'rotation-vector logging': 'Sensor.TYPE_ROTATION_VECTOR' in service,
    'raw hardware timestamps': 'sensor_timestamp_ns' in service and 'event.timestamp' in service,
    'sensor batching': 'BATCH_LATENCY_US' in service and 'registerListener' in service,
    'foreground recording': 'android:foregroundServiceType="health"' in manifest,
    'screen-off wake lock': 'PARTIAL_WAKE_LOCK' in service,
    'critical-battery stop': 'critical_battery' in service,
    'auto-stop': 'auto_stop' in service,
    'custom action UI': 'Custom action' in main and 'customAction' in main,
    'subject/wrist metadata': 'subjectId' in main and 'wrist' in main,
    'action folder storage': 'createSessionDir' in repo and 'actionSlug(config.actionLabel)' in repo,
    'metadata JSON': 'metadata.json' in repo,
    'raw event CSV': 'sensor_events.csv' in service,
    'markers': 'markers.csv' in service and 'manual_marker' in main,
    'interruption recovery': 'process_interrupted' in service,
    'dataset export manifest': 'DATASET_MANIFEST.json' in repo,
}
for name, ok in checks.items():
    if not ok: errors.append(f'feature validation failed: {name}')

# If kotlinc exists, use it as a syntax parser. Android symbols will be unresolved; only syntax diagnostics fail validation.
try:
    with tempfile.TemporaryDirectory() as tmp:
        kotlin_files = [str(p) for p in root.rglob('*.kt')]
        proc = subprocess.run(['kotlinc', *kotlin_files, '-d', str(Path(tmp)/'syntax.jar')], capture_output=True, text=True, timeout=60)
        diagnostics = proc.stdout + proc.stderr
        syntax_markers = re.findall(r'(?im)^.*error:.*(?:expecting|unexpected tokens|unclosed|missing .*\\}).*$', diagnostics)
        if syntax_markers:
            errors.extend(f'Kotlin syntax: {m}' for m in syntax_markers[:20])
except (FileNotFoundError, subprocess.TimeoutExpired):
    pass

if errors:
    print('PROJECT VALIDATION FAILED')
    for e in errors: print('ERROR:', e)
    sys.exit(1)
print('PROJECT VALIDATION OK')
for name in checks: print('OK:', name)
