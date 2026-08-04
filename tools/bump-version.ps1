<#
.SYNOPSIS
    Đặt phiên bản PearzCI ở mọi nơi cần thiết bằng một lệnh duy nhất.

.DESCRIPTION
    Phiên bản xuất hiện ở hai file và phải luôn khớp nhau:
      - package.json                       (Unity Package Manager)
      - resources/com/pearz/ci/version.txt (chuỗi hiện trong Telegram)

    Sửa tay từng file là nguồn gốc của việc thông báo Telegram báo sai
    phiên bản, nên hãy luôn chạy script này thay vì sửa trực tiếp.

.EXAMPLE
    pwsh ./tools/bump-version.ps1 -Version 0.4.10
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$packagePath = Join-Path $repositoryRoot 'package.json'
$versionPath = Join-Path $repositoryRoot 'resources/com/pearz/ci/version.txt'

foreach ($path in @($packagePath, $versionPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Không tìm thấy file bắt buộc: $path"
    }
}

# package.json: chỉ thay giá trị của khóa "version" ở cấp cao nhất để
# giữ nguyên định dạng và thứ tự khóa của file.
$packageText = Get-Content -LiteralPath $packagePath -Raw
$packagePattern = '(?m)^(\s*"version"\s*:\s*")[^"]*(")'

if ($packageText -notmatch $packagePattern) {
    throw "Không tìm thấy khóa `"version`" trong $packagePath"
}

$packageText = [regex]::Replace(
    $packageText,
    $packagePattern,
    "`${1}$Version`${2}",
    1)

Set-Content -LiteralPath $packagePath -Value $packageText -NoNewline

# version.txt giữ tiền tố "v" vì đây là chuỗi hiển thị trong Telegram.
Set-Content -LiteralPath $versionPath -Value "v$Version`n" -NoNewline

# Xác minh lại thay vì tin vào thao tác ghi.
$writtenPackage = (Get-Content -LiteralPath $packagePath -Raw |
    ConvertFrom-Json).version
$writtenVersion = (Get-Content -LiteralPath $versionPath -Raw).Trim()

if ($writtenPackage -ne $Version) {
    throw "package.json ghi ra '$writtenPackage', mong đợi '$Version'."
}

if ($writtenVersion -ne "v$Version") {
    throw "version.txt ghi ra '$writtenVersion', mong đợi 'v$Version'."
}

Write-Host "package.json  -> $writtenPackage"
Write-Host "version.txt   -> $writtenVersion"
Write-Host ''
Write-Host "Còn lại thủ công: thêm mục [$Version] vào CHANGELOG.md,"
Write-Host "commit, rồi tạo annotated tag v$Version."
