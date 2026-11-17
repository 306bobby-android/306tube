#!/usr/bin/env bash
# 1.2.3 -> 10203. Pre-release suffix is ignored, so 1.2.3-rc1 and 1.2.3 share a code.
set -euo pipefail
core="${1%%-*}"
IFS=. read -r major minor patch <<< "$core"
echo $(( major * 10000 + minor * 100 + patch ))
