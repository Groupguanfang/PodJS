import { afterEach, beforeEach, describe, expect, test } from "bun:test";

const events: unknown[] = [];
const emitted: Record<string, unknown>[] = [];

(globalThis as { pod?: unknown }).pod = {
  takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  emit: (line: string) => emitted.push(JSON.parse(line) as Record<string, unknown>),
  capabilities: () => JSON.stringify(["data.sqlite", "input.text"]),
};

const api = await import("../src/watch.ts");
const { HostServiceError, hostOperation, sqlite, browser } = await import("../src/services.ts");
const host = (globalThis as unknown as { pod: { capabilities: () => string } }).pod;

beforeEach(() => {
  host.capabilities = () => JSON.stringify(["data.sqlite", "input.text"]);
});
afterEach(() => { events.length = 0; emitted.length = 0; });

describe("host services", () => {
  test("opens only HTTP(S) URLs through the declared browser capability", async () => {
    host.capabilities = () => JSON.stringify(["system.browser"]);
    const operation = browser.open("https://example.com/about");
    expect(emitted[0]).toMatchObject({ method: "browser.open", args: { url: "https://example.com/about" } });
    operation.cancel();
    await expect(operation.result).rejects.toMatchObject({ code: "cancelled" });
  });

  test("rejects non-web browser URLs before emitting a request", async () => {
    expect(() => browser.open("file:///tmp/about.html")).toThrow("HTTP or HTTPS");
    expect(() => browser.open("javascript:alert(1)")).toThrow("HTTP or HTTPS");
    expect(emitted).toEqual([]);
  });

  test("rejects a service when its capability is absent", async () => {
    host.capabilities = () => JSON.stringify([]);
    const operation = hostOperation("sql.query", { sql: "select 1" });
    await expect(operation.result).rejects.toMatchObject({ code: "unsupported" });
    expect(emitted).toEqual([]);
  });

  test("matches responses by request id rather than completion order", async () => {
    const first = sqlite.query("select ?", [1]);
    const second = sqlite.query("select ?", [2]);
    const ids = emitted.map(event => event.id as number);
    expect(ids).toHaveLength(2);
    events.push({ t: "service.result", id: ids[1], ok: true, value: [{ value: 2 }] });
    events.push({ t: "service.result", id: ids[0], ok: true, value: [{ value: 1 }] });
    api.__pumpPodEvents();
    await expect(first).resolves.toEqual([{ value: 1 }]);
    await expect(second).resolves.toEqual([{ value: 2 }]);
  });

  test("cancellation rejects immediately and ignores a late host reply", async () => {
    const operation = hostOperation<string>("input.text", { title: "name" });
    const request = emitted[0];
    operation.cancel();
    expect(emitted[1]).toMatchObject({ t: "service.cancel", id: request.id });
    await expect(operation.result).rejects.toMatchObject({ code: "cancelled" });
    events.push({ t: "service.result", id: request.id, ok: true, value: "late" });
    api.__pumpPodEvents();
    expect(emitted).toHaveLength(2);
  });

  test("surfaces host error code and message", async () => {
    const result = sqlite.execute("bad");
    const id = emitted[0].id;
    events.push({ t: "service.result", id, ok: false, code: "invalid_argument", message: "bad SQL" });
    api.__pumpPodEvents();
    await expect(result).rejects.toEqual(new HostServiceError("invalid_argument", "bad SQL"));
  });
});
