#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
include_root="${PODJS_HARMONY_INCLUDE_ROOT:?Set PODJS_HARMONY_INCLUDE_ROOT to a native include directory containing arkui/ and hilog/}"
test_dir="$(mktemp -d /tmp/podjs-provider-test-XXXXXX)"
trap 'rm -f -- "$test_dir/provider-test"; rmdir -- "$test_dir"' EXIT
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined -g -pthread \
  -I"$include_root" -I"$project_root/crates/podjs-runtime/include" \
  -I"$project_root/platforms/harmony/entry/src/main/cpp" \
  "$project_root/tests/harmony-accessibility-provider.test.cpp" \
  "$project_root/platforms/harmony/entry/src/main/cpp/accessibility_provider.cpp" \
  -o "$test_dir/provider-test"
"$test_dir/provider-test"
