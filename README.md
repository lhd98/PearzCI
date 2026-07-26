# Pearz CI

Reusable Unity Editor build entry points for Pearz CI pipelines.

## Requirements

- Unity 6000.3 or newer
- Android Build Support when building Android players

## Installation

Add the package to `Packages/manifest.json`:

```json
{
  "dependencies": {
    "com.pearz.ci": "https://github.com/lhd98/PearzCI.git#v0.1.0"
  }
}
```

For private repositories, every developer and CI agent must have Git
credentials that can read `lhd98/PearzCI`.

## Android

Run Unity in batch mode with:

```text
-executeMethod Pearz.CI.BuildEntry.BuildAndroid
```

Configuration is read from environment variables. The commonly used variables
are:

- `OUTPUT_PATH`
- `BUILD_CONFIGURATION`
- `PRODUCT_NAME`
- `BUNDLE_IDENTIFIER`
- `SCRIPTING_DEFINE_SYMBOLS`
- `APP_VERSION`
- `ANDROID_VERSION_CODE`
- `TARGET_ARCHITECTURES`
- `KEYSTORE_PATH`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS_NAME`
- `KEY_ALIAS_PASSWORD`

See `BuildEntry.cs` for the complete list and accepted values.

## Versioning

Projects should pin a release tag such as `v0.1.0`. Avoid depending directly
on `main` in Jenkins builds.
