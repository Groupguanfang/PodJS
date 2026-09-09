package dev.podjs.runtime;

import android.bluetooth.BluetoothSocket;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking opaque framing shared by LAN and connected RFCOMM sockets.
 * Use IO workers, one reader and one writer; close interrupts both directions.
 * This layer does not authenticate or encrypt. Only pass received frames to the
 * session verifier, never directly to application storage or services.
 */
public final class PodSyncStream implements Closeable {
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    private final InputStream input;
    private final OutputStream output;
    private final Closeable connection;
    private final Object reader = new Object();
    private final Object writer = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PodSyncStream(Socket socket) throws IOException {
        this(socket.getInputStream(), socket.getOutputStream(), socket);
    }
    public PodSyncStream(BluetoothSocket socket) throws IOException {
        this(socket.getInputStream(), socket.getOutputStream(), socket);
    }
    PodSyncStream(InputStream input, OutputStream output, Closeable connection) {
        this.input = input; this.output = output; this.connection = connection;
    }
    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("Sync stream closed");
    }
    /** Null is clean EOF at a frame boundary. Partial EOF and malformed lengths fail closed. */
    public byte[] read() throws IOException {
        synchronized (reader) {
            ensureOpen();
            try {
                int first = input.read();
                if (first == -1) { close(); return null; }
                byte[] rest = new byte[3]; readFully(rest);
                long length = ((long)first << 24) | ((long)(rest[0] & 255) << 16)
                    | ((long)(rest[1] & 255) << 8) | (rest[2] & 255);
                if (length < 1 || length > MAX_FRAME_BYTES) throw new IOException("Invalid sync frame length");
                byte[] payload = new byte[(int)length]; readFully(payload);
                return payload;
            } catch (IOException error) { abort(error); throw error; }
        }
    }
    private void readFully(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException("Truncated sync frame");
            if (count == 0) throw new IOException("Sync stream made no progress");
            offset += count;
        }
    }
    /** Serializes whole frames; never interleaves payloads from concurrent senders. */
    public void write(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("Invalid sync frame size");
        synchronized (writer) {
            ensureOpen();
            try {
                int size = payload.length;
                output.write(new byte[]{(byte)(size >>> 24),(byte)(size >>> 16),(byte)(size >>> 8),(byte)size});
                output.write(payload); output.flush();
            } catch (IOException error) { abort(error); throw error; }
        }
    }
    private void abort(IOException error) {
        try { close(); } catch (IOException closing) { error.addSuppressed(closing); }
    }
    @Override public void close() throws IOException {
        // Do not acquire reader/writer monitors: either may be blocked in socket IO.
        if (closed.compareAndSet(false,true)) connection.close();
    }
}
