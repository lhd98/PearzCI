param(
    [Parameter(Mandatory = $true)]
    [string]$MetadataPath
)

$ErrorActionPreference = "Stop"

function Write-MetadataValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [AllowNull()]
        [object]$Value
    )

    if ($null -eq $Value) {
        return
    }

    $text = [string]$Value
    $text = $text -replace "(`r`n|`r|`n)", " "
    Write-Output "$Name=$text"
}

$metadata = Get-Content `
    -LiteralPath $MetadataPath `
    -Raw `
    -Encoding UTF8 |
    ConvertFrom-Json

Write-MetadataValue "RESULT" $metadata.result
Write-MetadataValue "PRODUCT_NAME" $metadata.productName
Write-MetadataValue "BUNDLE_IDENTIFIER" $metadata.bundleIdentifier
Write-MetadataValue "VERSION_NAME" $metadata.versionName
Write-MetadataValue "VERSION_CODE" $metadata.androidVersionCode
Write-MetadataValue "UNITY_VERSION" $metadata.unityVersion
Write-MetadataValue "SCRIPTING_BACKEND" $metadata.scriptingBackend
Write-MetadataValue "STRIPPING_LEVEL" $metadata.managedStrippingLevel
Write-MetadataValue "ORIENTATION" $metadata.orientation
Write-MetadataValue "OUTPUT_SIZE_BYTES" $metadata.outputSizeBytes
Write-MetadataValue "MAPPING_SIZE_BYTES" $metadata.mappingSizeBytes

foreach ($symbol in @($metadata.scriptingDefineSymbols)) {
    if (-not [string]::IsNullOrWhiteSpace([string]$symbol)) {
        Write-MetadataValue "DEFINE_SYMBOL" ([string]$symbol).Trim()
    }
}
