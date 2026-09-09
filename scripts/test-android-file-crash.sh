#!/usr/bin/env bash
# Run only against an explicitly selected development device with the current
# runtime-debug-androidTest.apk installed. The seed kills its OWN test process.
set -euo pipefail
serial=${1:?Usage: bash scripts/test-android-file-crash.sh DEVICE_SERIAL}
command -v adb >/dev/null
command -v jq >/dev/null
read -r crash_run </proc/sys/kernel/random/uuid
[[ $crash_run =~ ^[0-9a-f-]{36}$ ]]
adb -s "$serial" get-state
test_class='dev.podjs.runtime.PodSyncFileReconnectTest#fileCrashProbe'
runner='dev.podjs.runtime.test/androidx.test.runner.AndroidJUnitRunner'
assert_test_stopped() {
    local result status
    if result=$(adb -s "$serial" shell pidof dev.podjs.runtime.test 2>&1); then
        echo "Test process is still running: $result" >&2
        return 1
    else
        status=$?
        if [[ $status -ne 1 || -n $result ]]; then
            echo "Could not verify test process termination: $result" >&2
            return 1
        fi
    fi
}
assert_test_stopped
seed=$(adb -s "$serial" shell am instrument -w -e class "$test_class" -e fileCrashPhase seed -e fileCrashRun "$crash_run" "$runner")
echo "$seed"
[[ $seed == *"INSTRUMENTATION_STATUS: fileCrashReady=$crash_run"* ]]
[[ $seed == *"shortMsg=Process crashed."* ]]
checkpoint="no_backup/$crash_run-phone/file-crash-checkpoint.json"
before=$(adb -s "$serial" shell run-as dev.podjs.runtime.test cat "$checkpoint")
jq -e --arg run "$crash_run" '.run == $run and (.pid | type == "number") and .recovered != true' <<<"$before" >/dev/null
assert_test_stopped
resumed=$(adb -s "$serial" shell am instrument -w -e class "$test_class" -e fileCrashPhase resume -e fileCrashRun "$crash_run" "$runner")
echo "$resumed"
[[ $resumed == *"OK (1 test)"* ]]
after=$(adb -s "$serial" shell run-as dev.podjs.runtime.test cat "$checkpoint")
jq -e --arg run "$crash_run" '.run == $run and .recovered == true and .pid != .resumePid and .sessions == 3 and .bytes == 131079' <<<"$after" >/dev/null
echo "Verified SIGKILL recovery: $crash_run"
