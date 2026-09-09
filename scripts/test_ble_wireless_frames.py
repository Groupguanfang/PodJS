"""Radio-free framing checks for the explicit wireless probe."""
import importlib.util
import pathlib
import struct
import unittest
import asyncio

spec = importlib.util.spec_from_file_location("ble_probe", pathlib.Path(__file__).with_name("test-ble-wireless-central.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def packet(sequence, body):
    return b"\x50\x01" + struct.pack(">I", sequence) + body


class FramesTest(unittest.IsolatedAsyncioTestCase):
    async def test_daemon_guard_success_and_operation_error(self):
        async def owner():
            return "same"
        async def success():
            return 42
        async def failure():
            raise ValueError("fixture")
        self.assertEqual(42, await probe.stable_daemon(success, owner, 0.001))
        with self.assertRaisesRegex(ValueError, "fixture"):
            await probe.stable_daemon(failure, owner, 0.001)

    async def test_daemon_change_cancels_running_probe(self):
        calls = 0
        cancelled = asyncio.Event()
        async def owner():
            nonlocal calls
            calls += 1
            return "before" if calls == 1 else "after"
        async def operation():
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        with self.assertRaisesRegex(RuntimeError, "daemon changed"):
            await probe.stable_daemon(operation, owner, 0.001)
        self.assertTrue(cancelled.is_set())

    async def test_owner_query_failure_also_cancels_probe(self):
        calls = 0
        cancelled = asyncio.Event()
        async def owner():
            nonlocal calls
            calls += 1
            if calls > 1:
                raise RuntimeError("daemon unavailable")
            return "before"
        async def operation():
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        with self.assertRaisesRegex(RuntimeError, "daemon unavailable"):
            await probe.stable_daemon(operation, owner, 0.001)
        self.assertTrue(cancelled.is_set())
    async def test_fragmented_frame(self):
        frames = probe.Frames()
        body = bytes((i * 29) & 255 for i in range(4099))
        wire = struct.pack(">I", len(body)) + body
        for sequence, offset in enumerate(range(0, len(wire), 14)):
            frames.received(None, packet(sequence, wire[offset:offset + 14]))
        self.assertEqual(body, await frames.read())
        self.assertEqual(0, len(frames.buffer))

    async def test_bad_sequence_and_header(self):
        for value in (b"x", packet(1, b"x"), b"\x50\x02" + bytes(5)):
            frames = probe.Frames()
            frames.received(None, value)
            with self.assertRaises(ValueError):
                await frames.read()

    async def test_bad_length_and_value_budget(self):
        for value in (packet(0, bytes(4)), packet(0, struct.pack(">I", 4100)), packet(0, bytes(507))):
            frames = probe.Frames()
            frames.received(None, value)
            with self.assertRaises(ValueError):
                await frames.read()

    async def test_multiple_frames_and_copy_isolation(self):
        frames = probe.Frames()
        value = bytearray(packet(0, struct.pack(">I", 1) + b"a" + struct.pack(">I", 1) + b"b"))
        frames.received(None, value)
        value[:] = bytes(len(value))
        self.assertEqual(b"a", await frames.read())
        self.assertEqual(b"b", await frames.read())


if __name__ == "__main__":
    unittest.main()
