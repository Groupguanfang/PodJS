import { defineBackgroundHandler } from "../../../packages/framework/src/background-handler.ts";

export default defineBackgroundHandler(async context => {
  if (!context.request || context.taskId !== "refresh") return "failure";
  if ((context.payload as { origin?: string } | null)?.origin === "ui-guest") {
    const input = await context.request("kv.get", { key: "background-input" }) as { exists: boolean; value: number };
    if (!input.exists || input.value !== 17) return "failure";
    await context.request("kv.set", { key: "background-output", value: "后台😀" });
  }
  return "success";
});
