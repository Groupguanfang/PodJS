import { defineBackgroundHandler } from "../../packages/framework/src/background-handler.ts";

export default defineBackgroundHandler(async context => {
  if (context.taskId !== "refresh" || context.isCancelled()) return "failure";
  await Promise.resolve();
  return "success";
});
