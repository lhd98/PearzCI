#!/usr/bin/env bash
# Test android-install-devices.sh với adb giả. Chạy từ gốc repo.
set -euo pipefail

script="$PWD/resources/com/pearz/ci/android-install-devices.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# adb giả: STUB_DEVICES là các serial đang kết nối, STUB_MDNS là ip:port thấy
# qua mDNS (connect sẽ thêm vào danh sách), STUB_FAIL là serial cài lỗi.
cat > "$work/adb" <<'STUB'
#!/usr/bin/env bash
state="$STUB_STATE"
echo "$*" >> "$state/calls"
case "$1" in
    start-server|reconnect) exit 0 ;;
    mdns) printf 'List of discovered mdns services\n'
          for a in ${STUB_MDNS:-}; do printf 'adb-X\t_adb-tls-connect._tcp\t%s\n' "$a"; done ;;
    connect) echo "$2" >> "$state/connected"; echo "connected to $2" ;;
    devices) echo 'List of devices attached'
             for d in ${STUB_DEVICES:-} $(cat "$state/connected" 2>/dev/null); do
                 printf '%s\tdevice\n' "$d"
             done ;;
    -s) serial="$2"; shift 2
        case "$1" in
            get-serialno) echo "${STUB_SERIALNO:-$serial}" ;;
            install) [ "$serial" = "${STUB_FAIL:-}" ] && { echo 'Failure [INSTALL_FAILED]'; exit 1; }
                     echo Success ;;
            shell) echo "Model-$serial" ;;
        esac ;;
esac
exit 0
STUB
chmod +x "$work/adb"

run_case() {
    local name="$1" expected_status="$2" expected_result="$3"; shift 3
    local state="$work/$name"
    mkdir -p "$state/home"
    local status=0
    env -u PEARZ_ADB_SERIALS HOME="$state/home" STUB_STATE="$state" \
        ADB_EXE="$work/adb" OUTPUT_PATH="$work/app.apk" \
        PEARZ_ADB_RESULT_PATH="$state/result" "$@" \
        bash "$script" > "$state/log" 2>&1 || status=$?
    local result=""
    [ -f "$state/result" ] && result="$(cat "$state/result")"
    local installs
    installs="$(grep -c ' install ' "$state/calls" || true)"
    if [ "$status" != "$expected_status" ] || [ "$result" != "$expected_result" ]; then
        echo "FAIL $name: status=$status result='$result' installs=$installs (expected $expected_status '$expected_result')"
        sed 's/^/    /' "$state/log"
        failures=$((failures + 1))
    else
        echo "ok   $name"
    fi
}

run_case no-device 3 ""
run_case one-device 0 "Model-a" STUB_DEVICES=a
run_case two-devices 0 "Model-a, Model-b" STUB_DEVICES="a b"
run_case mdns-reconnect 0 "Model-10.0.0.5:4000" STUB_MDNS=10.0.0.5:4000
run_case duplicate-listing 0 "Model-a" STUB_DEVICES=a STUB_MDNS=10.0.0.5:4000 STUB_SERIALNO=SAME
run_case serial-filter 0 "Model-b" STUB_DEVICES="a b" PEARZ_ADB_SERIALS=b
run_case serial-missing 3 "" STUB_DEVICES=a PEARZ_ADB_SERIALS=zz
run_case install-fails 1 "Model-a" STUB_DEVICES="a b" STUB_FAIL=b

if [ "$failures" -ne 0 ]; then
    echo "$failures case(s) failed."
    exit 1
fi
echo "All android-install-devices.sh cases passed."
