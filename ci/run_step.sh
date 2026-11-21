#!/usr/bin/env bash
# Runs a CI step, keeps its full log, and on failure reports the tail of the log
# and the first compiler/CMake errors as GitHub check annotations. Annotations
# are readable through the checks API, which is how failures are triaged when
# the raw Actions logs are not at hand.
#
# Usage: ci/run_step.sh <step-name> <command> [args...]
set -uo pipefail

name="$1"; shift
log_dir="${RUNNER_TEMP:-/tmp}/ci-logs"
mkdir -p "${log_dir}"
log="${log_dir}/${name}.log"

"$@" 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}

if (( status != 0 )); then
    # GitHub keeps at most 10 error annotations per step; spend up to 8 on
    # distinct error lines and one on the log tail.
    {
        grep -nE "FAILED: " "${log}"
        grep -nE "(error:|CMake Error|fatal error|undefined reference|^e: |configure: error)" "${log}"
    } | awk '!seen[$0]++' | head -n 8 \
        | while IFS= read -r line; do
            msg="${line//'%'/'%25'}"
            echo "::error title=${name}: error line::${msg:0:900}"
        done
    # Ninja prints the failing command's output right after its FAILED: line.
    first_failed="$(grep -nm1 "FAILED: " "${log}" | cut -d: -f1)"
    if [[ -n "${first_failed}" ]]; then
        context="$(sed -n "${first_failed},$((first_failed + 60))p" "${log}")"
        context="${context//'%'/'%25'}"
        context="${context//$'\r'/}"
        context="${context//$'\n'/'%0A'}"
        echo "::error title=${name}: first failure::${context:0:60000}"
    fi
    tail_text="$(tail -n 60 "${log}")"
    tail_text="${tail_text//'%'/'%25'}"
    tail_text="${tail_text//$'\r'/}"
    tail_text="${tail_text//$'\n'/'%0A'}"
    echo "::error title=${name}: log tail (exit ${status})::${tail_text:0:60000}"
fi
exit "${status}"
