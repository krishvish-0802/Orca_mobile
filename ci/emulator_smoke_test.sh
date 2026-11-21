#!/usr/bin/env bash
# On a running emulator: slices the bundled 20 mm cube with orca_slice_cli,
# checks the result, then installs the app and checks that it starts cleanly.
#
# Usage: ci/emulator_smoke_test.sh <dir with orca_slice_cli, libc++_shared.so, *.apk>
set -euo pipefail

dir="$1"
remote=/data/local/tmp/orca
cube=core/data/src/main/assets/samples/calibration_cube_20mm.stl

adb shell mkdir -p "${remote}"
adb push "${dir}/orca_slice_cli" "${dir}/libc++_shared.so" "${remote}/"
adb push "${cube}" "${remote}/cube.stl"
adb shell chmod 755 "${remote}/orca_slice_cli"

run_cli() { adb shell "cd ${remote} && LD_LIBRARY_PATH=${remote} ./orca_slice_cli $*"; }

echo "== Engine version"
run_cli --version

echo "== Slicing the calibration cube"
started=${SECONDS}
output="$(run_cli cube.stl cube.gcode \
    printable_area=0x0,220x0,220x220,0x220 printable_height=250 \
    layer_height=0.2 initial_layer_print_height=0.2)"
echo "${output}"
echo "Sliced in $((SECONDS - started)) s"
result="$(echo "${output}" | grep '^{' | tail -n 1)"
echo "${result}" | grep -q '"status":"succeeded"' || { echo "Slicing did not succeed: ${result}"; exit 1; }
# 20 mm at 0.2 mm layers.
echo "${result}" | grep -q '"layerCount":100' || { echo "Expected 100 layers: ${result}"; exit 1; }

adb pull "${remote}/cube.gcode" "${dir}/cube.gcode"
moves="$(grep -c '^G1 ' "${dir}/cube.gcode" || true)"
echo "G-code: $(wc -c < "${dir}/cube.gcode") bytes, ${moves} G1 moves"
(( moves > 1000 )) || { echo "Too few moves in the G-code"; exit 1; }

echo "== Installing and launching the app"
apk="$(ls "${dir}"/*.apk | head -n 1)"
adb install -r "${apk}"
adb logcat -c
adb shell am start -W -n com.orcamobile.app/.MainActivity
sleep 10
if ! adb shell pidof com.orcamobile.app >/dev/null; then
    echo "The app is not running after launch"
    adb logcat -d | tail -n 200
    exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    adb logcat -d | grep -A 40 "FATAL EXCEPTION"
    exit 1
fi
echo "App started and is running."
