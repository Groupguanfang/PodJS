import dev.podjs.runtime.PodBleStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HexFormat;

/** JVM fixture using the real Android stream implementation, not copied codec. */
public final class BleWireInterop {
    public static void main(String[] args) throws Exception {
        int mtu = Integer.parseInt(args[0]);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        byte[] payload = HexFormat.of().parseHex(input.readLine());
        try (PodBleStream receiver = new PodBleStream(mtu, value -> {}, () -> {})) {
            String line;
            while ((line = input.readLine()) != null) receiver.receive(HexFormat.of().parseHex(line));
            if (!Arrays.equals(payload, receiver.framed().read())) throw new AssertionError("Harmony to Android mismatch");
        }
        try (PodBleStream sender = new PodBleStream(mtu, value -> System.out.println(HexFormat.of().formatHex(value)), () -> {})) {
            sender.framed().write(payload);
        }
    }
}
