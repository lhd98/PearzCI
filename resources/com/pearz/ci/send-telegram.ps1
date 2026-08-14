$ErrorActionPreference = "Stop"

$channelConfig = $env:TELEGRAM_CHANNEL

if ([string]::IsNullOrWhiteSpace($channelConfig)) {
    Write-Host "TELEGRAM_CHANNEL is empty. Telegram notification skipped."
    exit 0
}

$messageFile = $env:TELEGRAM_MESSAGE_FILE
$message = ""

if (
    -not [string]::IsNullOrWhiteSpace($messageFile) -and
    (Test-Path -LiteralPath $messageFile -PathType Leaf)
) {
    $message = Get-Content `
        -LiteralPath $messageFile `
        -Raw `
        -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($message)) {
    $messageLines = @(
        "BUILD SUCCESS"
        ""
        "Project: $env:JOB_BASE_NAME"
        "Artifact: $env:OUTPUT_FILE_NAME"
        "Build: #$env:BUILD_NUMBER"
        "Branch: $env:GIT_BRANCH"
        "Unity: $env:UNITY_VERSION"
        "Commit: $env:GIT_COMMIT_SHORT"
        "Message: $env:GIT_COMMIT_MESSAGE"
        ""
        "Download:"
        "$env:DOWNLOAD_URL"
    )

    $message = $messageLines -join "`n"
}
$targets = @(
    $channelConfig.Split(
        @(";"),
        [System.StringSplitOptions]::RemoveEmptyEntries
    )
)

if ($targets.Count -eq 0) {
    throw "TELEGRAM_CHANNEL does not contain any valid target."
}

$successCount = 0
$errors = New-Object System.Collections.Generic.List[string]

for ($index = 0; $index -lt $targets.Count; $index++) {
    $targetNumber = $index + 1
    $target = $targets[$index].Trim()

    if ([string]::IsNullOrWhiteSpace($target)) {
        continue
    }

    $parts = $target.Split(
        @("|"),
        3,
        [System.StringSplitOptions]::None
    )

    if ($parts.Count -lt 2) {
        $errors.Add(
            "Target #$targetNumber has invalid format. " +
            "Expected botToken|chatId|messageThreadId."
        )
        continue
    }

    $token = $parts[0].Trim()
    $chatId = $parts[1].Trim()
    $messageThreadId = if ($parts.Count -ge 3) {
        $parts[2].Trim()
    }
    else {
        ""
    }

    if ([string]::IsNullOrWhiteSpace($token)) {
        $errors.Add("Target #$targetNumber has an empty bot token.")
        continue
    }

    if ([string]::IsNullOrWhiteSpace($chatId)) {
        $errors.Add("Target #$targetNumber has an empty chat ID.")
        continue
    }

    if (
        -not [string]::IsNullOrWhiteSpace($messageThreadId) -and
        $messageThreadId -notmatch "^\d+$"
    ) {
        $errors.Add(
            "Target #$targetNumber has an invalid messageThreadId: " +
            $messageThreadId
        )
        continue
    }

    $uri = "https://api.telegram.org/bot$token/sendMessage"
    $body = @{
        chat_id = $chatId
        text = $message
        parse_mode = "HTML"
        disable_web_page_preview = "false"
    }

    if (-not [string]::IsNullOrWhiteSpace($messageThreadId)) {
        $body["message_thread_id"] = $messageThreadId
    }

    try {
        $response = Invoke-RestMethod `
            -Method Post `
            -Uri $uri `
            -ContentType "application/x-www-form-urlencoded; charset=utf-8" `
            -Body $body

        if (-not $response.ok) {
            throw "Telegram API returned ok=false."
        }

        $destination = if (
            [string]::IsNullOrWhiteSpace($messageThreadId)
        ) {
            "chat $chatId"
        }
        else {
            "chat $chatId, topic $messageThreadId"
        }

        Write-Host (
            "Telegram notification sent to target " +
            "#$targetNumber ($destination)."
        )
        $successCount++
    }
    catch {
        $errors.Add(
            "Target #$targetNumber failed for chat ${chatId}: " +
            $_.Exception.Message
        )
    }
}

Write-Host (
    "Telegram result: $successCount/$($targets.Count) target(s) succeeded."
)

if ($errors.Count -gt 0) {
    foreach ($sendError in $errors) {
        Write-Error $sendError
    }

    throw "One or more Telegram notifications failed."
}
