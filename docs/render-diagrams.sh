#!/usr/bin/env bash
set -euo pipefail

# Requires: npm i -g @mermaid-js/mermaid-cli
# Usage: ./docs/render-diagrams.sh [svg|png] (default svg)

FORMAT=${1:-svg}

OUT_DIR="$(cd "$(dirname "$0")" && pwd)/images"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)/diagrams"

mkdir -p "$OUT_DIR"

for src in "$SRC_DIR"/*.mmd; do
  name=$(basename "$src" .mmd)
  mmdc -i "$src" -o "$OUT_DIR/$name.$FORMAT"
  echo "Rendered $OUT_DIR/$name.$FORMAT"
done


