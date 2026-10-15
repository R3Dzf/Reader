#!/usr/bin/env bash
# Preserve actual UI images for diagnosis even when an instrumentation assertion fails.
set -u
result=0
./gradlew connectedDebugAndroidTest || result=$?
mkdir -p app/build/reports/reader-visuals
adb pull /sdcard/Pictures/ReaderTestVisuals/. app/build/reports/reader-visuals/ || {
    if [ "$result" -eq 0 ]; then result=1; fi
}
if [ "$result" -ne 0 ]; then exit "$result"; fi
python3 - <<'PY'
from pathlib import Path
root=Path('app/build/reports/reader-visuals')
expected=['powerpoint-reference.png','fonts-reference.png','chart-columns.png','chart-doughnut.png','chart-scatter.png','charts-gallery.png','rafolio-home-light.png','rafolio-history-ar.png','rafolio-home-dark.png','rafolio-empty.png','rafolio-settings.png','rafolio-update-available.png','rafolio-update-progress.png']
assert all((root/name).read_bytes()[:8]==bytes([137,80,78,71,13,10,26,10]) for name in expected)
PY
