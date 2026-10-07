#!/bin/sh

set -eu
set +x

channel_config=${TELEGRAM_CHANNEL:-}

if [ -z "$channel_config" ]; then
    echo "TELEGRAM_CHANNEL is empty. Telegram notification skipped."
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to send Telegram notifications." >&2
    exit 1
fi

trim_value() {
    printf '%s' "$1" |
        sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# Mặc định là Bot API chính thức. Đặt http://127.0.0.1:8081 để dùng
# telegram-bot-api chạy local (--local), cho phép gửi file đến 2 GB.
api_url=${TELEGRAM_API_URL:-https://api.telegram.org}
api_url=${api_url%/}
document_path=${TELEGRAM_DOCUMENT_PATH:-}

if [ -n "$document_path" ] && [ ! -f "$document_path" ]; then
    echo "TELEGRAM_DOCUMENT_PATH not found; sending the message only." >&2
    document_path=''
fi

# Gửi file build trả lời vào tin nhắn vừa gửi. Lỗi gửi file chỉ cảnh báo:
# tin nhắn có link Drive đã đến nơi rồi.
send_document() {
    doc_token=$1
    doc_chat_id=$2
    doc_thread_id=$3
    doc_reply_to=$4

    # curl -F coi ; và , trong tên file là cú pháp, nên bọc tên trong "".
    escaped_path=$(printf '%s' "$document_path" | sed 's/[\\"]/\\&/g')

    set -- --form-string "chat_id=$doc_chat_id" \
        --form-string "disable_notification=$telegram_silent" \
        -F "document=@\"$escaped_path\""

    if [ -n "$doc_thread_id" ]; then
        set -- "$@" --form-string "message_thread_id=$doc_thread_id"
    fi

    if [ -n "$doc_reply_to" ]; then
        set -- "$@" --form-string "reply_to_message_id=$doc_reply_to"
    fi

    doc_response=$(
        curl --fail --silent --show-error \
            --max-time 1800 \
            "$@" \
            "$api_url/bot${doc_token}/sendDocument"
    ) || doc_response=''

    if printf '%s' "$doc_response" |
        grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'; then
        echo "Build file sent to target #$target_count."
    else
        echo "WARNING: could not send the build file to target #$target_count." >&2
    fi
}

message_file=${TELEGRAM_MESSAGE_FILE:-}
message=''
telegram_silent=${TELEGRAM_SILENT:-false}

case "$telegram_silent" in
    true|false)
        ;;
    *)
        echo "TELEGRAM_SILENT must be true or false." >&2
        exit 1
        ;;
esac

if [ -n "$message_file" ] && [ -f "$message_file" ]; then
    message=$(cat "$message_file")
fi

if [ -z "$message" ]; then
    message=$(
        printf '%s\n' \
            "BUILD SUCCESS" \
            "" \
            "Project: ${JOB_BASE_NAME:-}" \
            "Artifact: ${OUTPUT_FILE_NAME:-}" \
            "Build: #${BUILD_NUMBER:-}" \
            "Branch: ${GIT_BRANCH:-}" \
            "Unity: ${UNITY_VERSION:-}" \
            "Commit: ${GIT_COMMIT_SHORT:-}" \
            "Message: ${GIT_COMMIT_MESSAGE:-}" \
            "" \
            "Download:" \
            "${DOWNLOAD_URL:-}"
    )
fi

success_count=0
target_count=0
error_count=0
remaining=$channel_config

while :; do
    case "$remaining" in
        *';'*)
            target=${remaining%%;*}
            remaining=${remaining#*;}
            ;;
        *)
            target=$remaining
            remaining=''
            ;;
    esac

    target=$(trim_value "$target")

    if [ -n "$target" ]; then
        target_count=$((target_count + 1))

        case "$target" in
            *'|'*)
                token=$(trim_value "${target%%|*}")
                target_rest=${target#*|}
                ;;
            *)
                echo "Target #$target_count has invalid format." >&2
                error_count=$((error_count + 1))
                token=''
                target_rest=''
                ;;
        esac

        if [ -n "$token" ]; then
            case "$target_rest" in
                *'|'*)
                    chat_id=$(trim_value "${target_rest%%|*}")
                    thread_id=$(trim_value "${target_rest#*|}")
                    ;;
                *)
                    chat_id=$(trim_value "$target_rest")
                    thread_id=''
                    ;;
            esac

            if [ -z "$chat_id" ]; then
                echo "Target #$target_count has an empty chat ID." >&2
                error_count=$((error_count + 1))
            elif [ -n "$thread_id" ] &&
                printf '%s' "$thread_id" | grep -Eq '[^0-9]'; then
                echo "Target #$target_count has an invalid messageThreadId." >&2
                error_count=$((error_count + 1))
            else
                uri="$api_url/bot${token}/sendMessage"

                if [ -n "$thread_id" ]; then
                    response=$(
                        curl --fail --silent --show-error \
                            --request POST \
                            --data-urlencode "chat_id=$chat_id" \
                            --data-urlencode "text=$message" \
                            --data-urlencode "parse_mode=HTML" \
                            --data-urlencode \
                                "disable_web_page_preview=false" \
                            --data-urlencode \
                                "disable_notification=$telegram_silent" \
                            --data-urlencode \
                                "message_thread_id=$thread_id" \
                            "$uri"
                    ) || response=''
                else
                    response=$(
                        curl --fail --silent --show-error \
                            --request POST \
                            --data-urlencode "chat_id=$chat_id" \
                            --data-urlencode "text=$message" \
                            --data-urlencode "parse_mode=HTML" \
                            --data-urlencode \
                                "disable_web_page_preview=false" \
                            --data-urlencode \
                                "disable_notification=$telegram_silent" \
                            "$uri"
                    ) || response=''
                fi

                if printf '%s' "$response" |
                    grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'; then
                    echo "Telegram notification sent to target #$target_count."
                    success_count=$((success_count + 1))

                    if [ -n "$document_path" ]; then
                        message_id=$(
                            printf '%s' "$response" |
                                sed -n 's/.*"message_id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' |
                                head -n 1
                        )
                        send_document "$token" "$chat_id" "$thread_id" "$message_id"
                    fi
                else
                    echo "Target #$target_count failed." >&2
                    error_count=$((error_count + 1))
                fi
            fi
        elif [ -n "$target_rest" ]; then
            echo "Target #$target_count has an empty bot token." >&2
            error_count=$((error_count + 1))
        fi
    fi

    [ -z "$remaining" ] && break
done

if [ "$target_count" -eq 0 ]; then
    echo "TELEGRAM_CHANNEL does not contain any valid target." >&2
    exit 1
fi

echo "Telegram result: $success_count/$target_count target(s) succeeded."

if [ "$error_count" -gt 0 ]; then
    exit 1
fi
