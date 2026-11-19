#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  printf 'Usage: %s /path/to/online-metadata-v0.4.9 /path/to/esbuild\n' "$0" >&2
  exit 2
fi

tool_dir="$(cd "$(dirname "$0")" && pwd)"
upstream="$(cd "$1" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cp -R "$upstream/src" "$tmp/src"
# QuickJS has no legacy RegExp.$1 property. Keep the capture from exec().
kw_lyric="$tmp/src/onlineResource/kw/lyric.ts"
sed 's/let time = RegExp\.\$1/let time = result[1]/' "$kw_lyric" > "$tmp/kw-lyric.ts"
mv "$tmp/kw-lyric.ts" "$kw_lyric"
# The Android host provides a fixed-length key for the NetEase request.
wy_crypto="$tmp/src/onlineResource/wy/utils/crypto.ts"
sed 's/String(Math.random()).substring(2, 18)/crypto.randomHex()/' "$wy_crypto" > "$tmp/wy-crypto.ts"
mv "$tmp/wy-crypto.ts" "$wy_crypto"
ln -s "$upstream/node_modules" "$tmp/node_modules"
cp "$tool_dir/catalog-entry.js" "$tmp/entry.js"
printf '{"compilerOptions":{"baseUrl":".","paths":{"@/*":["./src/*"]}}}\n' > "$tmp/tsconfig.json"
"$2" "$tmp/entry.js" --bundle --format=iife --global-name=OnlineMetadata \
  --tsconfig="$tmp/tsconfig.json" --target=es2020 --external:any-listen --minify-whitespace \
  --banner:js='/*! Any Listen Online Metadata v0.4.9 (Apache-2.0), includes he v1.2.0 (MIT). See assets/licenses. */' \
  --outfile="$tool_dir/../src/main/assets/online-metadata.js"

