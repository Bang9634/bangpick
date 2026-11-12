#!/bin/sh
set -eu

if ! command -v dot >/dev/null 2>&1; then
  echo 'Graphviz의 dot이 필요합니다.' >&2
  exit 1
fi

diagram_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
diagram_font=${DIAGRAM_FONT:-sans-serif}

for diagram_source in "$diagram_dir"/*.dot; do
  diagram_base=${diagram_source%.dot}
  dot -Gfontname="$diagram_font" -Nfontname="$diagram_font" -Efontname="$diagram_font" \
    -Tsvg "$diagram_source" -o "$diagram_base.svg"
  dot -Gfontname="$diagram_font" -Nfontname="$diagram_font" -Efontname="$diagram_font" \
    -Gdpi=160 -Tpng "$diagram_source" -o "$diagram_base.png"
done
