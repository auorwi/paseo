import { requireOptionalNativeModule } from "expo-modules-core";
import Constants from "expo-constants";
import { resolveAppVersion } from "@/utils/app-version";
import { createRecorder } from "./internal/recorder";

interface NativeRecorder {
  startRecorder(metadata: Record<string, string>): void;
  record(name: string, fields: Record<string, string>): void;
  heartbeat(): void;
}

const native =
  Constants.expoConfig?.extra?.watchdogBuild === true
    ? requireOptionalNativeModule<NativeRecorder>("PaseoNativeTrace")
    : null;
export const diagnostics = native
  ? createRecorder(
      (name, fields) => native.record(name, fields),
      () => performance.now(),
    )
  : null;
let started = false;

export function startDiagnostics(): void {
  if (!native || !diagnostics || started) return;
  started = true;
  native.startRecorder({
    version: resolveAppVersion() ?? "unknown",
    build: String(Constants.expoConfig?.extra?.diagnosticRevision ?? "unknown"),
    builtAt: String(Constants.expoConfig?.extra?.diagnosticBuildTime ?? "unknown"),
    reactProfiling: "true",
  });
  setInterval(() => native.heartbeat(), 250);
  setInterval(() => diagnostics.flushRenders(), 1000);
}
