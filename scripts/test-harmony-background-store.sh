#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/podjs-background-store-XXXXXX)"
trap 'rm -f -- "$test_dir/napi/native_api.h" "$test_dir/store.node" "$test_dir/companion.node" "$test_dir/journal.cjs" "$test_dir/companion.cjs" "$test_dir/incoming-storage"; rmdir -- "$test_dir/napi" "$test_dir"' EXIT
mkdir "$test_dir/napi"
ln -s /usr/include/node/node_api.h "$test_dir/napi/native_api.h"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DNODE_GYP_MODULE_NAME=background_store_test \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-background-store-addon.cpp" -o "$test_dir/store.node"
node "$project_root/tests/harmony-background-store.test.cjs" "$test_dir/store.node"
bun build "$project_root/platforms/harmony/entry/src/main/ets/BackgroundJournal.ts" \
  --target=node --format=cjs --outfile "$test_dir/journal.cjs"
node "$project_root/tests/harmony-background-journal-native.test.cjs" "$test_dir/store.node" "$test_dir/journal.cjs"
bun build "$project_root/tests/fixtures/companion-state-exports.ts" \
  --target=node --format=cjs --outfile "$test_dir/companion.cjs"
node "$project_root/tests/harmony-companion-state-native.test.cjs" "$test_dir/store.node" "$test_dir/companion.cjs"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fPIC -shared -pthread \
  -I/usr/include/node -I"$test_dir" -DPODJS_FILE_STORAGE_HOST_TEST \
  "$project_root/platforms/harmony/companion/src/main/cpp/napi_init.cpp" -lcrypto -o "$test_dir/companion.node"
node "$project_root/tests/harmony-companion-state-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs" companion-only
node "$project_root/tests/harmony-companion-outbox-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs"
node "$project_root/tests/harmony-companion-pairing-lease-native.test.cjs" "$test_dir/companion.node"
node "$project_root/tests/harmony-companion-file-requests-native.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs"
node "$project_root/tests/harmony-incoming-file-napi.test.cjs" "$test_dir/companion.node" "$test_dir/companion.cjs"
"${CXX:-clang++}" -std=c++17 -Wall -Wextra "$project_root/tests/harmony-incoming-file-storage.test.cpp" -lcrypto -o "$test_dir/incoming-storage"
"$test_dir/incoming-storage"
