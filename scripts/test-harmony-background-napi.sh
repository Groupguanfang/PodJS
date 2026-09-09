#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/podjs-background-addon-XXXXXX)"
trap 'rm -f -- "$test_dir/napi/native_api.h" "$test_dir/background.node" "$test_dir/execution.cjs"; rmdir -- "$test_dir/napi" "$test_dir"' EXIT
mkdir "$test_dir/napi"
ln -s /usr/include/node/node_api.h "$test_dir/napi/native_api.h"
cargo build --manifest-path "$project_root/Cargo.toml" -p podjs-runtime
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DNODE_GYP_MODULE_NAME=background_test \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  -I"$project_root/crates/podjs-runtime/include" \
  "$project_root/tests/harmony-background-napi-addon.cpp" \
  "${CARGO_TARGET_DIR:-$project_root/target}/debug/libpodjs_runtime.a" \
  -ldl -lm -o "$test_dir/background.node"
node "$project_root/tests/harmony-background-napi.test.cjs" "$test_dir/background.node"
node "$project_root/tests/harmony-background-lease.test.cjs" "$test_dir/background.node"
bun build "$project_root/tests/fixtures/harmony-background-execution-exports.ts" \
  --target=node --format=cjs --outfile "$test_dir/execution.cjs"
node "$project_root/tests/harmony-background-execution-native.test.cjs" "$test_dir/background.node" "$test_dir/execution.cjs"
