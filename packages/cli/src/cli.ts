#!/usr/bin/env bun
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { POD_TARGETS, type PodTargetProfile } from "../../framework/src/targets.ts";

const ROOT = resolve(import.meta.dir, "../../..");
type Target = PodTargetProfile["id"];

function fail(message: string): never {
  console.error(`pod: ${message}`);
  process.exit(1);
}

function targetArg(args: string[]): Target {
  const raw = args.find(value => value.startsWith("--target="))?.slice(9) ?? args[0];
  if (!raw || !(raw in POD_TARGETS)) {
    fail(`--target wants one of ${Object.keys(POD_TARGETS).join(", ")}`);
  }
  return raw as Target;
}

function run(cmd: string[], cwd = ROOT, env?: Record<string, string>): void {
  const child = Bun.spawnSync(cmd, {
    cwd,
    env: { ...process.env, ...env },
    stdout: "inherit",
    stderr: "inherit",
  });
  if (child.exitCode !== 0) fail(`${cmd.join(" ")} failed (${child.exitCode})`);
}

function capture(cmd: string[]): string | null {
  try {
    const result = Bun.spawnSync(cmd, { cwd: ROOT, stdout: "pipe", stderr: "pipe" });
    return result.exitCode === 0 ? result.stdout.toString().trim() : null;
  } catch {
    return null;
  }
}

function fnv1a64(bytes: Uint8Array): string {
  let hash = 0xcbf29ce484222325n;
  for (const byte of bytes) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 0x100000001b3n);
  return hash.toString(16).padStart(16, "0");
}

function doctor(): void {
  const revision = capture(["git", "-C", "vendor/pocketjs", "rev-parse", "HEAD"]);
  const expected = "0a90bf904d835210e52a11ed275a86d0040b5086";
  const checks: [string, boolean, string][] = [
    ["PocketJS revision", revision === expected, revision ?? "missing"],
    ["Bun", !!capture(["bun", "--version"]), capture(["bun", "--version"]) ?? "missing"],
    ["Rust", !!capture(["rustc", "--version"]), capture(["rustc", "--version"]) ?? "missing"],
    ["ADB", !!capture(["adb", "version"]), capture(["adb", "version"])?.split("\n")[0] ?? "missing"],
    ["Android SDK", !!process.env.ANDROID_SDK_ROOT, process.env.ANDROID_SDK_ROOT ?? "missing"],
    ["Android NDK 27.2", !!process.env.ANDROID_SDK_ROOT && existsSync(join(process.env.ANDROID_SDK_ROOT, "ndk/27.2.12479018")), "required for ARMv7/ARM64"],
    ["Android CMake 3.22", !!process.env.ANDROID_SDK_ROOT && existsSync(join(process.env.ANDROID_SDK_ROOT, "cmake/3.22.1/bin/cmake")), "required by JNI renderer"],
    ["Xcode (external Mac allowed)", !!capture(["xcodebuild", "-version"]), capture(["xcodebuild", "-version"]) ?? "not on this host"],
    ["HDC (external DevEco host allowed)", !!capture(["hdc", "version"]), capture(["hdc", "version"]) ?? "not on this host"],
  ];
  for (const [name, ok, detail] of checks) {
    console.log(`${ok ? "OK " : "-- "}${name}: ${detail}`);
  }
  const devices = capture(["adb", "devices", "-l"]);
  if (devices) console.log(devices);
  if (revision !== expected) process.exitCode = 1;
}

function build(target: Target, app = "apps/gallery/src/main.tsx"): void {
  const profile = POD_TARGETS[target];
  const density = target === "android-watch" || target === "wearos-watch" ? 2 : 2;
  const out = join(ROOT, "dist", target);
  run([
    "bun", "vendor/pocketjs/tools/build.ts", resolve(ROOT, app),
    "--framework=solid", `--density=${density}`, "--hz=60",
    `--font-regular=${join(ROOT, "assets/fonts/NotoSansCJKSC-Regular-subset.ttf")}`,
    `--font-bold=${join(ROOT, "assets/fonts/NotoSansCJKSC-Bold-subset.ttf")}`,
    `--outdir=${out}`, `--project-root=${ROOT}`,
  ]);
  const output = basename(app).replace(/\.tsx?$/, "");
  const js = join(out, `${output}.js`);
  const pak = join(out, `${output}.pak`);
  if (!existsSync(js) || !existsSync(pak)) fail(`PocketJS did not produce ${js} and ${pak}`);
  const manifest = {
    schema: 1,
    target: profile.id,
    hostAbi: profile.hostAbi,
    logicalViewport: profile.logicalViewport,
    renderer: profile.renderer,
    capabilities: profile.capabilities,
    pocketjsRevision: "0a90bf904d835210e52a11ed275a86d0040b5086",
    bundleHash: fnv1a64(readFileSync(js)),
    pakHash: fnv1a64(readFileSync(pak)),
  };
  writeFileSync(join(out, "pod.manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`pod: built ${profile.id} -> ${out}`);
}

function test(): void {
  run(["bun", "test", "packages/framework/tests/watch.test.ts", "tests/targets.test.ts"]);
  run(["cargo", "test", "--workspace"]);
}

function packageTarget(target: Target): void {
  build(target);
  if (target === "android-watch" || target === "wearos-watch") {
    run(["bash", "scripts/build-android-runtime.sh"]);
    const app = target === "android-watch" ? ":androidApp" : ":wearApp";
    run(["./gradlew", `${app}:assembleDebug`, `${app}:bundleRelease`, ":runtime:assembleRelease"], join(ROOT, "platforms/android"));
  } else if (target === "watchos-watch") {
    if (!capture(["xcodebuild", "-version"])) fail("watchOS packaging must run on the configured Mac");
    run([
      "xcodebuild", "-project", "platforms/watchos/PodJSWatchApp.xcodeproj",
      "-scheme", "PodJSWatchApp", "-destination", "generic/platform=watchOS",
      "-derivedDataPath", ".pod/watchos", "CODE_SIGNING_ALLOWED=NO", "build",
    ]);
  } else {
    if (process.platform === "win32") {
      run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-harmony-runtime.ps1"]);
    } else if (process.platform === "darwin") {
      run(["bash", "scripts/build-harmony-runtime.sh"]);
    } else {
      fail("HarmonyOS packaging requires DevEco Studio on Windows or macOS");
    }
  }
}

const [command = "doctor", ...args] = process.argv.slice(2);
switch (command) {
  case "doctor": doctor(); break;
  case "build": build(targetArg(args)); break;
  case "test": test(); break;
  case "package": packageTarget(targetArg(args)); break;
  default: fail("usage: pod <doctor|build|test|package> [--target=<watch target>]");
}
