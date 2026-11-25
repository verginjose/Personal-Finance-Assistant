#!/bin/sh
set -eu
cd "${1:-/usr/share/nginx/html}"
# One content-derived directory versions the complete ES-module graph, including
# relative imports. Hashing the entry point alone would leave stale dependencies.
version=$( (find js css assets -type f -exec sha256sum {} \; ; sha256sum index.html) | LC_ALL=C sort | sha256sum | cut -c1-20)
base="/static/$version"
mkdir -p "static/$version"
cp -R js css assets "static/$version/"
# App-owned images use absolute paths in HTML and JS templates. Uploaded images
# live under /api and are deliberately outside this static cache.
find "static/$version" -type f \( -name '*.js' -o -name '*.css' \) -exec sed -i "s|/assets/|$base/assets/|g" {} +
sed -i -e "s|src=\"js/|src=\"$base/js/|g" \
       -e "s|href=\"css/|href=\"$base/css/|g" \
       -e "s|/assets/|$base/assets/|g" index.html
