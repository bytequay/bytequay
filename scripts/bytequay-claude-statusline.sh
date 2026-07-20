#!/usr/bin/env bash

# Capture only Claude's provider-reported plan limits for ByteQuay, then
# preserve an existing status-line command (for example Ponytail's badge).
input=$(cat)
cache_dir="${BYTEQUAY_DATA_DIR:-$HOME/Library/Application Support/ByteQuay}"
cache_file="$cache_dir/claude-statusline.json"

if command -v jq >/dev/null 2>&1 && [ -n "$input" ]; then
    mkdir -p "$cache_dir"
    temp_file=$(mktemp "$cache_dir/.claude-statusline.XXXXXX")
    if printf '%s' "$input" | jq '{model, rate_limits}' > "$temp_file"; then
        mv "$temp_file" "$cache_file"
    else
        rm -f "$temp_file"
    fi
fi

if [ "${1:-}" = "--" ]; then
    shift
fi
if [ "$#" -gt 0 ]; then
    printf '%s' "$input" | "$@"
fi
