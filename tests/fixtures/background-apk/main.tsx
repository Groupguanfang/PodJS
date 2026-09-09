import { mount } from "@pocketjs/framework/solid";
import { Text } from "@pocketjs/framework/components";
import { background, notifications } from "../../../packages/framework/src/platform-services.ts";
import { kv } from "../../../packages/framework/src/watch.ts";

kv.set("background-input",17);
for (const kind of ["open", "action"] as const) {
  const handler = async (event: import("../../../packages/framework/src/platform-services.ts").NotificationEvent) => {
    if (!event.notificationId.startsWith("sdk-")) throw Error("Not this fixture's notification");
    const permission = await notifications.permission().result;
    kv.set("notification-" + kind, JSON.stringify({ ...event, permission }));
  };
  if (kind === "open") notifications.onOpen(handler);
  else notifications.onAction(handler);
}
background.register({ id: "refresh", handler: "refresh", earliestAt: 0, payload: { origin: "ui-guest" } })
  .result.catch(error => console.error("Background guest registration failed",String(error)));

mount(() => <Text>Background package test</Text>);
