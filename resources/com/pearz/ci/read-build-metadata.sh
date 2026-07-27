#!/usr/bin/env sh

set -eu

metadata_path="${1:?Metadata path is required.}"

python3 - "$metadata_path" <<'PY'
import json
import sys


def emit(name, value):
    if value is None:
        return

    text = str(value).replace("\r", " ").replace("\n", " ")
    print(f"{name}={text}")


with open(sys.argv[1], "r", encoding="utf-8-sig") as metadata_file:
    metadata = json.load(metadata_file)

emit("RESULT", metadata.get("result"))
emit("PRODUCT_NAME", metadata.get("productName"))
emit("BUNDLE_IDENTIFIER", metadata.get("bundleIdentifier"))
emit("VERSION_NAME", metadata.get("versionName"))
emit("VERSION_CODE", metadata.get("androidVersionCode"))
emit("UNITY_VERSION", metadata.get("unityVersion"))
emit("SCRIPTING_BACKEND", metadata.get("scriptingBackend"))
emit("STRIPPING_LEVEL", metadata.get("managedStrippingLevel"))
emit("ORIENTATION", metadata.get("orientation"))
emit("OUTPUT_SIZE_BYTES", metadata.get("outputSizeBytes"))
emit("MAPPING_SIZE_BYTES", metadata.get("mappingSizeBytes"))

for symbol in metadata.get("scriptingDefineSymbols") or []:
    text = str(symbol).strip()

    if text:
        emit("DEFINE_SYMBOL", text)
PY
