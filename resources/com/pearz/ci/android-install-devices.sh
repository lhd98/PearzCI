#!/usr/bin/env bash
# Cài APK ($OUTPUT_PATH) lên mọi máy Android adb thấy, nối lại máy Wi-Fi bị rớt.
# Env: ADB_EXE, OUTPUT_PATH, PEARZ_ADB_RESULT_PATH, PEARZ_ADB_SERIALS (tuỳ chọn).
# Exit: 0 = đã cài, 3 = không có máy, khác = lỗi cài.
set -u
rm -f "$PEARZ_ADB_RESULT_PATH"
# Jenkins bỏ hẳn biến môi trường khi giá trị rỗng, nên phải có mặc định
# để `set -u` không dừng script khi ANDROID_DEVICE_SERIAL để trống.
serial_filter="${PEARZ_ADB_SERIALS:-}"

# adb khác version với server đang chạy sẽ tự kill server đó khi start-server,
# làm rớt mọi máy Wi-Fi của người dùng Mac. Có server rồi thì dùng đúng binary
# adb đang chạy server ấy thay cho bản đã dò (thường là bản đi kèm Unity).
server_port="${ANDROID_ADB_SERVER_PORT:-5037}"
server_pid=$(lsof -nP -iTCP:"$server_port" -sTCP:LISTEN -t 2>/dev/null | head -1)
if [ -n "$server_pid" ]; then
    server_exe=$(ps -o comm= -p "$server_pid" 2>/dev/null)
    if [ -n "$server_exe" ] && [ -x "$server_exe" ] && [ "$server_exe" != "$ADB_EXE" ]; then
        echo "adb server is already running from $server_exe; using it instead of $ADB_EXE"
        ADB_EXE="$server_exe"
    fi
fi

JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe             "$ADB_EXE" start-server || exit 1

list_devices() {
    "$ADB_EXE" devices |
        awk 'NR > 1 && $2 == "device" { print $1 }'
}

# Kết nối Wi-Fi hay bị adb làm rớt trong khi
# điện thoại vẫn tưởng còn kết nối. Trước khi cài,
# nối lại: phiên offline, máy adb thấy qua mDNS và
# các địa chỉ đã cài thành công lần trước.
known_file="$HOME/.pearz-ci/adb-known-devices.txt"
mkdir -p "$(dirname "$known_file")"
"$ADB_EXE" reconnect offline >/dev/null 2>&1 || true
{
    "$ADB_EXE" mdns services 2>/dev/null |
        awk '$2 ~ /^_adb(-tls-connect)?\._tcp/ { print $3 }'
    [ -f "$known_file" ] && cat "$known_file"
} | grep -E '^[0-9.]+:[0-9]+$' | sort -u |
while read -r address; do
    echo "adb connect $address"
    output=$("$ADB_EXE" connect "$address" 2>&1 | head -1)
    echo "$output"
    case "$output" in
        *"connected to"*) echo "$address" >> "$known_file.new" ;;
    esac
done
# Giữ tối đa 20 địa chỉ còn kết nối được gần nhất.
if [ -f "$known_file.new" ]; then
    tail -n 20 "$known_file.new" > "$known_file"
    rm -f "$known_file.new"
fi

attempt=0
while [ -z "$(list_devices)" ] && [ "$attempt" -lt 5 ]; do
    sleep 1
    attempt=$((attempt + 1))
done

"$ADB_EXE" devices -l
connected=$(list_devices)

serials=""
if [ -n "$serial_filter" ]; then
    for serial in $serial_filter; do
        if printf '%s\n' "$connected" | grep -Fqx "$serial"; then
            serials="$serials $serial"
        else
            echo "Device $serial is not connected; skipped."
        fi
    done
else
    serials="$connected"
fi

if [ -z "$(echo $serials)" ]; then
    echo "No Android device is connected; install skipped."
    exit 3
fi

failed=0
installed=""
done_ids=" "
for serial in $serials; do
    # Một máy có thể xuất hiện hai lần (tên mDNS
    # và ip:port); chỉ cài một lần theo serialno.
    device_id=$("$ADB_EXE" -s "$serial" get-serialno 2>/dev/null | tr -d '\r')
    case "$done_ids" in
        *" ${device_id:-$serial} "*) continue ;;
    esac
    done_ids="$done_ids${device_id:-$serial} "

    echo "Installing $OUTPUT_PATH on $serial"
    if "$ADB_EXE" -s "$serial" install -r -d "$OUTPUT_PATH"; then
        model=$("$ADB_EXE" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
        installed="$installed, ${model:-$serial}"
    else
        echo "ERROR: Install failed on $serial"
        failed=1
    fi
done

printf '%s' "${installed#, }" > "$PEARZ_ADB_RESULT_PATH"
exit "$failed"
