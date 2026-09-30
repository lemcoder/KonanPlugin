#!/usr/bin/env bash
# Checks out third_party/kotlin with only kotlin-native/Interop, at the commit this repo pins.
# A plain `git submodule update --init` would check out the whole Kotlin repository: sparse-checkout
# is local config and is not recorded in .gitmodules.
set -euo pipefail
cd "$(dirname "$0")/.."

path=third_party/kotlin
url=$(git config -f .gitmodules "submodule.$path.url")
sha=$(git ls-tree HEAD "$path" | awk '{print $3}')

git submodule init "$path"
if [ ! -e "$path/.git" ]; then
    git init -q "$path"
    git -C "$path" remote add origin "$url"
fi
git -C "$path" fetch -q --depth 1 --filter=blob:none origin "$sha"
git -C "$path" sparse-checkout set kotlin-native/Interop
git -C "$path" checkout -q --detach "$sha"
git submodule absorbgitdirs "$path"
