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

# Runs the CLI and prints everything it said, even when it fails: a bare
# $(run_cli ...) under set -e would exit before showing why. Sets `output`.
capture() {
    local status=0
    output="$(run_cli "$@" 2>&1)" || status=$?
    echo "${output}" | tail -n 60
    if (( status != 0 )); then
        echo "orca_slice_cli exited with ${status}"
        echo "== Native crash report, if any"
        adb logcat -d -b crash | tail -n 80 || true
        adb logcat -d | grep -E "orca|DEBUG|libc   " | tail -n 80 || true
        exit 1
    fi
}

echo "== Engine version"
run_cli --version

echo "== Slicing the calibration cube"
started=${SECONDS}
capture cube.stl cube.gcode \
    printable_area=0x0,220x0,220x220,0x220 printable_height=250 \
    layer_height=0.2 initial_layer_print_height=0.2
echo "Sliced in $((SECONDS - started)) s"
result="$(echo "${output}" | grep '^{' | tail -n 1)"
echo "${result}" | grep -q '"status":"succeeded"' || { echo "Slicing did not succeed: ${result}"; exit 1; }
# 20 mm at 0.2 mm layers.
echo "${result}" | grep -q '"layerCount":100' || { echo "Expected 100 layers: ${result}"; exit 1; }

adb pull "${remote}/cube.gcode" "${dir}/cube.gcode"
moves="$(grep -c '^G1 ' "${dir}/cube.gcode" || true)"
echo "G-code: $(wc -c < "${dir}/cube.gcode") bytes, ${moves} G1 moves"
(( moves > 1000 )) || { echo "Too few moves in the G-code"; exit 1; }

echo "== Reading the G-code back for the preview"
capture --preview cube.gcode
preview="$(echo "${output}" | grep '^{' | tail -n 1)"
echo "${preview}" | grep -q '"error"' && { echo "Preview failed: ${preview}"; exit 1; }
layers="$(echo "${preview}" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["layerZs"]))')"
(( layers == 100 )) || { echo "Expected 100 preview layers, got ${layers}"; exit 1; }

echo "== Slicing with OrcaSlicer system presets (Creality)"
adb push "${dir}/test-profiles.tar.gz" "${remote}/"
adb shell "cd ${remote} && rm -rf profiles && tar -xzf test-profiles.tar.gz"
printer="Creality Ender-3 Pro 0.4 nozzle"
capture --profiles "${remote}/profiles" --list-vendors
capture --profiles "${remote}/profiles" --compatible Creality "\"${printer}\""
compatible="$(echo "${output}" | grep '^{' | tail -n 1)"
echo "Compatible with ${printer}: ${compatible:0:400}..."
process="$(echo "${compatible}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["defaultProcess"])')"
filament="$(echo "${compatible}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["defaultFilament"])')"
echo "Using process '${process}', filament '${filament}'"
[[ -n "${process}" && -n "${filament}" ]] || { echo "No default process/filament for ${printer}"; exit 1; }
capture cube.stl cube-ender3.gcode --profiles "${remote}/profiles" --vendor Creality \
    --printer "\"${printer}\"" --process "\"${process}\"" --filament "\"${filament}\""
result="$(echo "${output}" | grep '^{' | tail -n 1)"
echo "${result}" | grep -q '"status":"succeeded"' || { echo "Preset slice did not succeed: ${result}"; exit 1; }
adb pull "${remote}/cube-ender3.gcode" "${dir}/cube-ender3.gcode"
# The printer profile's start G-code must be in the output.
grep -qi "printer_model\|Ender-3" "${dir}/cube-ender3.gcode" || { echo "G-code does not mention the printer"; exit 1; }
echo "Preset slice OK: $(wc -c < "${dir}/cube-ender3.gcode") bytes"

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
