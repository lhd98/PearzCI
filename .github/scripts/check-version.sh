#!/usr/bin/env bash
# Kiểm tra version của PearzCI nhất quán giữa package.json, version.txt và
# CHANGELOG.md. Với BASE_REF (PR), version đổi thì phải tăng và chưa có tag.
set -euo pipefail

version="$(jq -r '.version' package.json)"
errors=0
fail() { echo "ERROR: $*" >&2; errors=1; }

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    fail "package.json version '$version' is not X.Y.Z."
fi

version_txt="$(tr -d '[:space:]' < resources/com/pearz/ci/version.txt)"
if [[ "$version_txt" != "v$version" ]]; then
    fail "resources/com/pearz/ci/version.txt is '$version_txt', expected 'v$version'."
fi

first_heading="$(grep -m1 -oE '^## \[[^]]+\]' CHANGELOG.md | sed -E 's/^## \[(.*)\]$/\1/')"
if [[ "$first_heading" != "$version" ]]; then
    fail "The first CHANGELOG.md section is '$first_heading', expected '$version'."
fi

duplicates="$(grep -oE '^## \[[^]]+\]' CHANGELOG.md | sort | uniq -d)"
if [[ -n "$duplicates" ]]; then
    fail "Duplicate CHANGELOG.md sections: $(echo "$duplicates" | tr '\n' ' ')"
fi

if [[ -n "${BASE_REF:-}" ]]; then
    base_version="$(git show "$BASE_REF:package.json" | jq -r '.version')"
    if [[ "$version" != "$base_version" ]]; then
        highest="$(printf '%s\n%s\n' "$base_version" "$version" | sort -V | tail -1)"
        if [[ "$highest" != "$version" ]]; then
            fail "Version $version is lower than $base_version on the base branch."
        fi
        if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
            fail "Tag v$version already exists; bump to a new version."
        fi
    fi
fi

if [[ "$errors" -ne 0 ]]; then
    exit 1
fi
echo "Version $version is consistent."
