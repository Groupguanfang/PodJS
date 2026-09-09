#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/podjs-journal-addon-XXXXXX)"
trap 'rm -f -- "$test_dir/napi/native_api.h" "$test_dir/journal.node"; rmdir -- "$test_dir/napi" "$test_dir"' EXIT
mkdir "$test_dir/napi"
ln -s /usr/include/node/node_api.h "$test_dir/napi/native_api.h"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DNODE_GYP_MODULE_NAME=journal_test \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-journal-napi-addon.cpp" -o "$test_dir/journal.node"
node "$project_root/tests/harmony-journal-napi.test.cjs" "$test_dir/journal.node"
