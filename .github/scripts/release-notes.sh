#!/usr/bin/env bash
# Fills .github/release-template.md with the tag and its CHANGELOG.md section.
set -euo pipefail
tag="$1"
root="$(cd "$(dirname "$0")/../.." && pwd)"

changes="$(awk -v tag="$tag" '
    /^## / {
        if (found) exit
        v = $2; gsub(/[\[\]]/, "", v)
        if (v == tag) { found = 1; next }
    }
    found
' "$root/CHANGELOG.md" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac)"

if [ -z "$changes" ]; then
    echo "CHANGELOG.md has no entries under '## $tag'" >&2
    exit 1
fi

while IFS= read -r line; do
    case "$line" in
        '{{CHANGELOG}}') printf '%s\n' "$changes" ;;
        *) printf '%s\n' "${line//'{{VERSION}}'/$tag}" ;;
    esac
done < "$root/.github/release-template.md"
