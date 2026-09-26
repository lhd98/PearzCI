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

message_file=${TELEGRAM_MESSAGE_FILE:-}
message=''

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
                uri="https://api.telegram.org/bot${token}/sendMessage"

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
                            "$uri"
                    ) || response=''
                fi

                if printf '%s' "$response" |
                    grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'; then
                    echo "Telegram notification sent to target #$target_count."
                    success_count=$((success_count + 1))
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
