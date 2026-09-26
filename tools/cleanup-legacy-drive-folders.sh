#!/usr/bin/env bash
# Dọn các thư mục Google Drive theo layout cũ (trước PearzCI 0.6.66), khi mỗi
# build có một thư mục riêng:
#   <root>/<job>/apk/<version>-<build>/   <root>/<job>/aab/...   <root>/<job>/ios/...
#   <root>/<job>/<version>-<build>/
# Layout mới <root>/<job>/<APP_VERSION>/ không bị động tới.
#
# Mặc định chỉ liệt kê (dry run). Thêm --delete để xoá; rclone đưa file vào
# Thùng rác của Drive nên vẫn khôi phục được trong 30 ngày.
#
#   tools/cleanup-legacy-drive-folders.sh
#   tools/cleanup-legacy-drive-folders.sh --job FoodSort --delete
set -euo pipefail

remote="gdrive"
root="JenkinsBuild"
rclone="rclone"
only_job=""
delete=false

usage() {
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    echo
    echo "Options: --remote NAME (gdrive) --root PATH (JenkinsBuild)"
    echo "         --rclone PATH (rclone) --job NAME --delete"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --remote) remote="$2"; shift 2 ;;
        --root) root="$2"; shift 2 ;;
        --rclone) rclone="$2"; shift 2 ;;
        --job) only_job="$2"; shift 2 ;;
        --delete) delete=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

base="$remote:$root"
total=0

human_size() {
    "$rclone" size --json "$1" 2>/dev/null |
        sed -E 's/.*"bytes":([0-9]+).*/\1/' |
        awk '{ split("B KB MB GB TB", u); s = $1; i = 1
               while (s >= 1024 && i < 5) { s /= 1024; i++ }
               printf "%.1f %s", s, u[i] }'
}

handle() {
    local path="$1"
    echo "  $path ($(human_size "$base/$path"))"
    total=$((total + 1))
    if [ "$delete" = true ]; then
        "$rclone" purge "$base/$path"
        echo "    deleted"
    fi
}

jobs="$("$rclone" lsf "$base" --dirs-only)"
[ -n "$only_job" ] && jobs="$only_job/"

while IFS= read -r job; do
    [ -z "$job" ] && continue
    job="${job%/}"
    echo "$job"
    while IFS= read -r entry; do
        entry="${entry%/}"
        case "$entry" in
            apk|aab|ios) handle "$job/$entry" ;;
            *)
                if [[ "$entry" =~ ^[0-9]+(\.[0-9]+)*-[0-9]+$ ]]; then
                    handle "$job/$entry"
                fi
                ;;
        esac
    done < <("$rclone" lsf "$base/$job" --dirs-only 2>/dev/null)
done <<< "$jobs"

echo
if [ "$delete" = true ]; then
    echo "Deleted $total legacy folder(s)."
else
    echo "Found $total legacy folder(s). Run again with --delete to remove them."
fi
