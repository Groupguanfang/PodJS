#!/usr/bin/env python3
"""Explicit BlueZ central for PodBleWirelessTest. No pairing or app credentials.

Run with: uv run --with bleak==3.0.2 scripts/test-ble-wireless-central.py
Start the Android fixture with this host adapter's address first. Only a single
advertiser of the PodJS UUID is accepted; no reconnect or pairing is attempted.
"""
import asyncio
import hashlib
import json
import struct

SERVICE = "deef0001-654d-4e33-9a27-1341d8c28fd1"
TX = "deef0002-654d-4e33-9a27-1341d8c28fd1"
RX = "deef0003-654d-4e33-9a27-1341d8c28fd1"


async def bluez_owner():
    """Read-only daemon identity. This does not enable an adapter or start a scan."""
    process = await asyncio.create_subprocess_exec(
        "busctl", "--system", "--json=short", "call", "org.freedesktop.DBus",
        "/org/freedesktop/DBus", "org.freedesktop.DBus", "GetNameOwner", "s", "org.bluez",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        output, _ = await asyncio.wait_for(process.communicate(), 3)
        if process.returncode:
            raise RuntimeError("Cannot resolve the BlueZ daemon identity; probe stopped")
        value = json.loads(output)
        if value.get("type") != "s" or len(value.get("data", [])) != 1:
            raise RuntimeError("Invalid BlueZ daemon identity reply")
        return value["data"][0]
    finally:
        if process.returncode is None:
            process.kill()
            await process.wait()


async def stable_daemon(operation, owner=bluez_owner, interval=0.25):
    """Cancel this probe when BlueZ disappears/restarts; never restart the daemon.

    Cancellation remains cooperative with the BLE backend's cleanup. This is not
    a crash prevention mechanism or permission to retry a failed wireless run.
    """
    initial = await owner()
    task = asyncio.create_task(operation())
    try:
        while True:
            done, _ = await asyncio.wait({task}, timeout=interval)
            if await owner() != initial:
                raise RuntimeError("BlueZ daemon changed during probe; cancelled without retry")
            if done:
                return await task
    finally:
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


class Frames:
    def __init__(self):
        self.sequence = 0
        self.buffer = bytearray()
        self.ready = asyncio.Queue(maxsize=4)
        self.failed = None

    def received(self, _characteristic, value):
        if self.failed:
            return
        try:
            if not 6 < len(value) <= 512 or value[:2] != b"\x50\x01":
                raise ValueError("invalid packet")
            sequence = int.from_bytes(value[2:6], "big")
            if sequence != self.sequence:
                raise ValueError("packet sequence gap/replay")
            self.sequence += 1
            self.buffer.extend(value[6:])
            if len(self.buffer) > 8192:
                raise ValueError("receive budget exceeded")
            while len(self.buffer) >= 4:
                length = int.from_bytes(self.buffer[:4], "big")
                if not 0 < length <= 4099:
                    raise ValueError("frame budget exceeded")
                if len(self.buffer) < 4 + length:
                    break
                self.ready.put_nowait(bytes(self.buffer[4:4 + length]))
                del self.buffer[:4 + length]
        except Exception as error:
            self.failed = error

    async def read(self):
        if self.failed:
            raise self.failed
        result = await asyncio.wait_for(self.ready.get(), 30)
        if self.failed:
            raise self.failed
        return result


async def main():
    from bleak import BleakClient, BleakScanner
    found = await BleakScanner.discover(timeout=4, service_uuids=[SERVICE])
    if len(found) != 1:
        raise RuntimeError(f"Expected exactly one PodJS test advertiser, found {len(found)}")
    frames = Frames()
    async with BleakClient(found[0], pair=False, timeout=15) as client:
        await client.start_notify(RX, frames.received, bluez={"use_start_notify": True})
        greeting = json.loads(await frames.read())
        mtu = greeting["mtu"]
        if not isinstance(mtu, int) or not 23 <= mtu <= 517:
            raise ValueError("invalid negotiated MTU")
        packet_payload = min(mtu - 3, 512) - 6
        sequence = 0

        async def write(body):
            nonlocal sequence
            wire = struct.pack(">I", len(body)) + body
            for offset in range(0, len(wire), packet_payload):
                value = b"\x50\x01" + struct.pack(">I", sequence) + wire[offset:offset + packet_payload]
                await asyncio.wait_for(client.write_gatt_char(TX, value, response=True), 10)
                sequence += 1

        body = bytes((i * 29) & 255 for i in range(4099))
        await write(body)
        received = await frames.read()
        if received != body[::-1]:
            raise ValueError("wireless reply differs")
        await write(b"verified")
        if await frames.read() != b"done":
            raise ValueError("missing final confirmation")
        print(json.dumps({"mtu": mtu, "bytes_each_way": len(body),
                          "source_sha256": hashlib.sha256(body).hexdigest(), "verified": True}))


if __name__ == "__main__":
    asyncio.run(stable_daemon(lambda: asyncio.wait_for(main(), 80)))
