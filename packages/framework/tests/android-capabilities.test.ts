import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { POD_TARGETS } from "../src/targets";

test("Android boot capabilities match packaged profiles including their ABI order", () => {
  const source = readFileSync(new URL("../../../platforms/android/runtime/src/main/cpp/jni_bridge.cpp", import.meta.url), "utf8");
  const literal = source.match(/const char\* capabilities = ("(?:\\.|[^"\\])*");/)?.[1];
  expect(literal).toBeDefined();
  const capabilities = JSON.parse(JSON.parse(literal!));
  expect(capabilities).toEqual(POD_TARGETS["android-watch"].capabilities);
  expect(capabilities).toEqual(POD_TARGETS["wearos-watch"].capabilities);
});
