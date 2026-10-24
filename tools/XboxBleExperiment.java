//SOURCES NativeMacDialog.java
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.io.*;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static java.lang.foreign.ValueLayout.*;

class XboxBleExperiment {
    static final HexFormat HEX = HexFormat.of();
    static final String VENDOR = "000000015f604c4f9c83a7953298d40d";
    static final byte[] STOP_RUMBLE = HEX.parseHex("0f00000000000000");
    static Map<String, List<Characteristic>> expectedSchema;
    static byte[] expectedSerial;
    static final class Serial implements AutoCloseable {
        final Arena memory = Arena.ofShared();
        final Linker linker = Linker.nativeLinker();
        final SymbolLookup libc = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", memory);
        final BlockingQueue<String> incoming = new ArrayBlockingQueue<>(8192);
        final BufferedWriter log;
        final int fd;
        volatile boolean closed;
        volatile Throwable failure;
        final Thread reader;

        Serial(String port) throws Throwable {
            if (!port.matches("/dev/cu\\.usbmodem[A-Za-z0-9]+"))
                throw new IllegalArgumentException("Expected an explicit /dev/cu.usbmodem... port");
            Path directory = Path.of("target", "xiao", "runs");
            Files.createDirectories(directory);
            Path path = directory.resolve(Instant.now().toString().replace(':', '-') + ".log");
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            log = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            System.out.println("Private transcript: " + path.toAbsolutePath());
            Process stty = new ProcessBuilder("stty", "-f", port, "115200", "raw", "-echo", "-ixon", "-ixoff").inheritIO().start();
            if (stty.waitFor() != 0) throw new IOException("Cannot configure " + port);
            // Darwin O_RDWR | O_NOCTTY | O_NONBLOCK keeps reads cancellable.
            fd = (int) call("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), memory.allocateFrom(port), 2 | 0x20000 | 4);
            if (fd < 0) throw new IOException("Cannot open " + port);
            reader = Thread.ofPlatform().daemon().name("xiao-serial").start(() -> {
                try {
                    MemorySegment buffer = memory.allocate(4096);
                    StringBuilder partial = new StringBuilder();
                    while (!closed) {
                        long size = (long) call("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), fd, buffer, 4096L);
                        if (size < 0) {
                            int errno = ((MemorySegment) call("__error", FunctionDescriptor.of(ADDRESS))).reinterpret(4).get(JAVA_INT, 0);
                            if (errno != 35 && errno != 4) throw new IOException("Serial read errno=" + errno);
                        }
                        if (size <= 0) { Thread.sleep(20); continue; }
                        for (byte b : buffer.asSlice(0, size).toArray(JAVA_BYTE)) {
                            if (b == '\r') continue;
                            if (b == '\n') {
                                String line = partial.toString(); partial.setLength(0);
                                record("< " + line);
                                if (!incoming.offer(line)) throw new IOException("Serial response queue overflow");
                            } else {
                                if (partial.length() >= 1200) throw new IOException("Oversized serial response");
                                partial.append((char) Byte.toUnsignedInt(b));
                            }
                        }
                    }
                } catch (Throwable error) { if (!closed) failure = error; }
            });
        }

        Object call(String name, FunctionDescriptor type, Object... args) throws Throwable {
            return linker.downcallHandle(libc.find(name).orElseThrow(), type).invokeWithArguments(args);
        }

        synchronized void record(String text) throws IOException {
            log.write(Instant.now() + " " + text); log.newLine(); log.flush();
            System.out.println(text);
        }

        void send(String command) throws Throwable {
            if (!command.matches("[A-Z0-9a-f_ ]{1,590}")) throw new IllegalArgumentException("Invalid serial command");
            record("> " + command);
            byte[] data = (command + "\n").getBytes(StandardCharsets.US_ASCII);
            MemorySegment bytes = memory.allocateFrom(JAVA_BYTE, data);
            long count = (long) call("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), fd, bytes, (long)data.length);
            if (count != data.length) throw new IOException("Incomplete serial write; stopping");
        }

        List<String> await(String prefix, int seconds) throws Throwable {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            List<String> result = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                NativeMacDialog.pumpEvents();
                if (failure != null) throw new IOException("Serial reader failed", failure);
                String line = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                result.add(line);
                if (line.startsWith(prefix)) return result;
                if (line.startsWith("ERROR ") || (line.startsWith("DISCONNECTED ") && !prefix.equals("STATUS ")))
                    throw new IOException(line);
            }
            throw new IOException("Timed out waiting for " + prefix + "; stopping to avoid mismatched responses");
        }

        List<String> request(String command, String prefix) throws Throwable {
            for (int attempt = 0; ; attempt++) {
                send(command);
                try { return await(prefix, 20); }
                catch (IOException failure) {
                    if (!retryBusy(command, failure.getMessage(), attempt)) throw failure;
                    record("RETRY GATT_BUSY " + command + " attempt=" + (attempt + 1));
                    NativeMacDialog.pause(100);
                }
            }
        }

        static boolean retryBusy(String command, String response, int attempt) {
            // SoftDevice rejects busy discovery/read requests before queuing them; writes are never retried.
            return attempt < 10 && "ERROR SOFTDEVICE 00000011".equals(response)
                    && Set.of("SERVICES", "CHARS", "DESCS", "READ").contains(command.split(" ", 2)[0]);
        }

        String last(String command, String prefix) throws Throwable {
            return request(command, prefix).getLast();
        }

        @Override public void close() throws Exception {
            closed = true;
            try { call("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), fd); }
            catch (Throwable error) { throw new IOException(error); }
            reader.join();
            memory.close();
            synchronized (this) { log.close(); }
        }
    }

    record Service(int start, int end, String uuid) {}
    record Characteristic(int declaration, int handle, int properties, String uuid, int end) {}
    record Descriptor(int handle, String uuid) {}
    static int number(String value) { return Integer.parseInt(value, 16); }
    static String handle(int value) { return "%04x".formatted(value); }

    static int status(String response) { return number(response.split(" ")[1]); }
    static void requireSuccess(String response) throws IOException {
        if (status(response) != 0) throw new IOException("ATT operation failed: " + response);
    }

    static List<Service> services(Serial serial) throws Throwable {
        List<Service> found = new ArrayList<>();
        int start = 1;
        while (start <= 65535) {
            List<String> response = serial.request("SERVICES " + handle(start), "DONE SERVICES ");
            int status = number(response.getLast().split(" ")[2]);
            if (status == 0x010a) break;
            if (status != 0) throw new IOException(response.getLast());
            int next = start;
            for (String line : response) if (line.startsWith("SERVICE ")) {
                String[] fields = line.split(" ");
                Service service = new Service(number(fields[1]), number(fields[2]), fields[3]);
                found.add(service); next = service.end + 1;
            }
            if (next <= start) throw new IOException("Service discovery made no progress");
            start = next;
        }
        return found;
    }

    static List<Characteristic> characteristics(Serial serial, Service service) throws Throwable {
        List<Characteristic> found = new ArrayList<>();
        int start = service.start;
        while (start <= service.end) {
            List<String> response = serial.request("CHARS " + handle(start) + " " + handle(service.end), "DONE CHARS ");
            int status = number(response.getLast().split(" ")[2]);
            if (status == 0x010a) break;
            if (status != 0) throw new IOException(response.getLast());
            int next = start;
            for (String line : response) if (line.startsWith("CHAR ")) {
                String[] f = line.split(" ");
                found.add(new Characteristic(number(f[1]), number(f[2]), number(f[3]), f[4], service.end));
                next = number(f[2]) + 1;
            }
            if (next <= start) throw new IOException("Characteristic discovery made no progress");
            start = next;
        }
        for (int i = 0; i + 1 < found.size(); i++) {
            Characteristic c = found.get(i);
            found.set(i, new Characteristic(c.declaration, c.handle, c.properties, c.uuid, found.get(i + 1).declaration - 1));
        }
        return found;
    }

    static List<Descriptor> descriptors(Serial serial, Characteristic c) throws Throwable {
        List<Descriptor> found = new ArrayList<>();
        int start = c.handle + 1;
        while (start <= c.end) {
            List<String> response = serial.request("DESCS " + handle(start) + " " + handle(c.end), "DONE DESCS ");
            int status = number(response.getLast().split(" ")[2]);
            if (status == 0x010a) break;
            if (status != 0) throw new IOException(response.getLast());
            int next = start;
            for (String line : response) if (line.startsWith("DESC ")) {
                String[] f = line.split(" ");
                found.add(new Descriptor(number(f[1]), f[2])); next = number(f[1]) + 1;
            }
            if (next <= start) throw new IOException("Descriptor discovery made no progress");
            start = next;
        }
        return found;
    }

    static byte[] read(Serial serial, int attribute) throws Throwable {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (data.size() < 512) {
            String response = serial.last("READ " + handle(attribute) + " " + handle(data.size()), "READ ");
            String[] f = response.split(" ", -1);
            // Exact multiples of the default MTU's 22-byte read payload end with Invalid Offset.
            if (status(response) == 0x0107 && data.size() > 0) break;
            requireSuccess(response);
            if (number(f[2]) != attribute || number(f[3]) != data.size()) throw new IOException("Mismatched read response");
            byte[] chunk = HEX.parseHex(f[4]); data.writeBytes(chunk);
            if (chunk.length < 22) break;
        }
        return data.toByteArray();
    }

    static String write(Serial serial, int attribute, byte[] payload) throws Throwable {
        String response = serial.last("WRITE " + handle(attribute) + " " + HEX.formatHex(payload), "WRITE ");
        if (number(response.split(" ")[2]) != attribute) throw new IOException("Mismatched write response");
        return response;
    }

    static void connect(Serial serial) throws Throwable {
        while (true) {
            NativeMacDialog.proceed("Pair the Xbox controller with the XIAO",
                    "1. Unplug the controller's USB cable. Keep the XIAO connected.\n\n"
                    + "2. In System Settings > Bluetooth, disconnect the Xbox controller if connected. Keep Bluetooth on.\n\n"
                    + "3. Turn the controller on. Hold its small Pair button beside the USB port for three seconds, until the Xbox light flashes rapidly.\n\n"
                    + "4. Click OK once it is flashing. The XIAO will scan for 30 seconds.\n\n"
                    + "After testing, you may need to pair the controller back to the Mac.");
            NativeMacDialog.progress("Finding the controller", "Scanning through the XIAO for up to 30 seconds...");
            serial.request("SCAN", "OK SCAN");
            List<String> response;
            try { response = serial.await("ADV ", 30); }
            catch (IOException timeout) {
                serial.request("STOP", "OK STOP");
                if (serial.failure != null) throw timeout;
                continue;
            }
            serial.request("STOP", "OK STOP");
            String[] adv = response.getLast().split(" ");
            // The prompt requests fresh pairing; discard only this public peer's stale XIAO key.
            if (adv[2].equals("0")) serial.request("FORGET " + adv[1] + " 0", "OK FORGET");
            NativeMacDialog.progress("Connecting to the controller", "Waiting for the XIAO's Bluetooth connection and pairing...");
            serial.send("CONNECT " + adv[1] + " " + adv[2]);
            serial.await("CONNECTED ", 30);
            serial.request("PAIR", "OK PAIR");
            for (int i = 0; i < 50; i++) {
                String current = serial.last("STATUS", "STATUS ");
                if (current.startsWith("STATUS 1 1")) return;
                if (current.startsWith("STATUS 0 ")) throw new IOException("Disconnected before pairing established encryption");
                NativeMacDialog.pause(200);
            }
            throw new IOException("Pairing did not establish encryption");
        }
    }

    static void ensureConnected(Serial serial) throws Throwable {
        if (serial.last("STATUS", "STATUS ").startsWith("STATUS 1 1")) return;
        connect(serial);
        Map<String, List<Characteristic>> actual = schema(serial);
        if (!actual.equals(expectedSchema)) throw new IOException("GATT schema changed after reconnect; stopping before writes");
        Characteristic identity = actual.get("180a").stream().filter(c -> c.uuid.equals("2a25")).findFirst().orElseThrow();
        if (!Arrays.equals(read(serial, identity.handle), expectedSerial)) throw new IOException("Different controller after reconnect");
    }

    static Map<String, List<Characteristic>> schema(Serial serial) throws Throwable {
        Map<String, List<Characteristic>> result = new LinkedHashMap<>();
        for (Service service : services(serial)) result.put(service.uuid, characteristics(serial, service));
        return result;
    }

    static byte[] led(int sequence, int brightness, boolean padded) {
        if (sequence < 1 || sequence > 255 || brightness < 0 || brightness > 47)
            throw new IllegalArgumentException("Invalid LED sequence or brightness");
        byte[] packet = new byte[padded ? 13 : 7];
        System.arraycopy(new byte[]{0x0a, 0x20, (byte)sequence, 3, 0, 1, (byte)brightness}, 0, packet, 0, 7);
        return packet;
    }

    static boolean sequence(Serial serial, String name, int attribute, boolean padded, int output) throws Throwable {
        NativeMacDialog.proceed("Observe the controller light", name + "\n\n"
                + "1. Watch the Xbox button.\n\n"
                + "2. Click OK when ready. Four writes will alternate brightness steps 0 and 20, three seconds apart.\n\n"
                + "3. Afterwards, report whether the light changed.\n\nHID writes may cause brief rumble.");
        ensureConnected(serial);
        try {
            for (int i = 0; i < 4; i++) {
                NativeMacDialog.progress(name, "Write " + (i + 1) + " of 4: requesting brightness step "
                        + (i % 2 == 0 ? 0 : 20) + ".\n\nWatch the Xbox button. The observation prompt follows this sequence.");
                String result = write(serial, attribute, led(i + 1, i % 2 == 0 ? 0 : 20, padded));
                serial.record("ATT_RESULT " + name + " " + result);
                NativeMacDialog.progress(name, "Write " + (i + 1) + " of 4: "
                        + (status(result) == 0 ? "accepted" : "rejected, ATT status 0x" + handle(status(result)))
                        + ".\n\nWatch the Xbox button. Next step in three seconds.");
                NativeMacDialog.pause(3000);
            }
        } finally { requireSuccess(write(serial, output, STOP_RUMBLE)); }
        int observation = NativeMacDialog.choose("Did the brightness change?", name
                + "\n\nDid the Xbox light alternate in time with the four writes?\n\n"
                + "Choose Unchanged if it stayed steady. A disconnect or pairing flash does not count.", "Changed", "Unchanged", "Cancel");
        if (observation != 0 && observation != 1) throw new CancellationException("Observation cancelled");
        serial.record("OBSERVATION " + name + " " + (observation == 0 ? "changed" : "unchanged"));
        return observation == 0;
    }

    static void writeCommand(Serial serial, int attribute, int value) throws Throwable {
        String result = serial.last(controlPointCommand(attribute, value), "SENT ");
        if (number(result.split(" ")[1]) != attribute) throw new IOException("Mismatched write-command completion");
    }

    static String controlPointCommand(int attribute, int value) {
        if (attribute < 1 || attribute > 65535 || (value != 0 && value != 1))
            throw new IllegalArgumentException("Expected a HID Control Point handle and command 00 or 01");
        return "WRITE_CMD " + handle(attribute) + " " + "%02x".formatted(value);
    }

    static String suspendTest(Serial serial, List<Characteristic> hid, int inputCcc) throws Throwable {
        Characteristic control = hid.stream().filter(c -> c.uuid.equals("2a4c") && (c.properties & 4) != 0)
                .findFirst().orElseThrow();
        NativeMacDialog.proceed("Test Bluetooth HID Suspend/Resume",
                "1. Watch the Xbox light.\n\n2. Click OK when ready. The XIAO will send HID Suspend (00), "
                + "wait five seconds, then send Exit Suspend (01).\n\n3. Report whether the light went out and came back.\n\n"
                + "This tests standard power management. It does not request an intensity value.");
        ensureConnected(serial);
        boolean stayedConnected;
        try {
            NativeMacDialog.progress("HID Suspend", "Sending 00 to the discovered HID Control Point.\n\nWatch the Xbox light for five seconds.");
            writeCommand(serial, control.handle, 0);
            NativeMacDialog.pause(5000);
            stayedConnected = serial.last("STATUS", "STATUS ").startsWith("STATUS 1 1");
        } finally {
            // Resume even when observation or status collection fails.
            writeCommand(serial, control.handle, 1);
        }
        if (!stayedConnected) throw new IOException("Suspend lost the connection; no brightness result established");
        NativeMacDialog.progress("HID resumed", "Sent Exit Suspend (01).\n\nCheck whether the Xbox light returned.");
        NativeMacDialog.pause(2000);
        int answer = NativeMacDialog.choose("Did Suspend control the light?",
                "Did the light go out during the five-second pause and return after resume?\n\n"
                + "SENT confirms local radio transmission. Write Commands have no controller ATT response.",
                "Changed", "Unchanged", "Cancel");
        if (answer != 0 && answer != 1) throw new CancellationException("Suspend observation cancelled");
        serial.record("OBSERVATION HID Suspend/Resume " + (answer == 0 ? "changed" : "unchanged"));
        requireSuccess(write(serial, inputCcc, new byte[]{1, 0}));
        String before = serial.last("STATUS", "STATUS ");
        NativeMacDialog.proceed("Check input after resume", "1. Move a stick or press a controller button.\n\n2. Click OK to check whether input notifications increased.");
        String after = serial.last("STATUS", "STATUS ");
        serial.record("INPUT_AFTER_RESUME before=" + before + " after=" + after);
        return answer == 0 ? "A light change was reported for HID Suspend/Resume. This controls suspend state, not brightness steps."
                : "HID Suspend/Resume produced no reported light change.";
    }

    static String run(Serial serial, String mode) throws Throwable {
        connect(serial);
        NativeMacDialog.progress("Inspecting the controller", "Reading identity, firmware and Bluetooth HID reports...");
        Map<String, List<Characteristic>> schema = schema(serial);
        expectedSchema = schema;
        List<Characteristic> info = Objects.requireNonNull(schema.get("180a"), "Missing Device Information service");
        Characteristic pnp = info.stream().filter(c -> c.uuid.equals("2a50")).findFirst().orElseThrow();
        byte[] identity = read(serial, pnp.handle);
        if (!HEX.formatHex(identity).startsWith("025e04130b") || identity.length != 7)
            throw new IOException("Expected Microsoft Series X|S 045e:0b13; stopping before writes");
        Characteristic fw = info.stream().filter(c -> c.uuid.equals("2a26")).findFirst().orElseThrow();
        serial.record("FIRMWARE " + new String(read(serial, fw.handle), StandardCharsets.UTF_8));
        Characteristic serialNumber = info.stream().filter(c -> c.uuid.equals("2a25")).findFirst().orElseThrow();
        expectedSerial = read(serial, serialNumber.handle);
        List<Characteristic> hid = Objects.requireNonNull(schema.get("1812"), "Missing HID service");
        Characteristic map = hid.stream().filter(c -> c.uuid.equals("2a4b")).findFirst().orElseThrow();
        serial.record("REPORT_MAP " + HEX.formatHex(read(serial, map.handle)));
        int output = 0, inputCcc = 0;
        for (Characteristic c : hid) if (c.uuid.equals("2a4d")) {
            List<Descriptor> descs = descriptors(serial, c);
            Descriptor reference = descs.stream().filter(d -> d.uuid.equals("2908")).findFirst().orElseThrow();
            String report = HEX.formatHex(read(serial, reference.handle));
            if (report.equals("0302") && (c.properties & 8) != 0) output = c.handle;
            if (report.equals("0101")) inputCcc = descs.stream().filter(d -> d.uuid.equals("2902")).findFirst().orElseThrow().handle;
        }
        if (output == 0 || inputCcc == 0) throw new IOException("Expected HID input 01/output 03 missing");
        if (mode.equals("--inspect")) return "Inspection completed. No characteristic writes were sent.";
        if (mode.equals("--suspend-test")) return suspendTest(serial, hid, inputCcc);
        requireSuccess(write(serial, inputCcc, new byte[]{1, 0}));
        NativeMacDialog.proceed("Check controller input", "1. Press a controller button or move a stick.\n\n"
                + "2. Click OK. The runner will check that input notifications arrived.");
        ensureConnected(serial);
        requireSuccess(write(serial, inputCcc, new byte[]{1, 0}));
        if (Long.parseLong(serial.last("STATUS", "STATUS ").split(" ")[3]) == 0)
            throw new IOException("No input notification received; stopping before LED experiments");
        requireSuccess(write(serial, output, STOP_RUMBLE));
        int vendor = Objects.requireNonNull(schema.get(VENDOR), "Missing vendor service").stream()
                .filter(c -> c.uuid.equals("000000045f604c4f9c83a7953298d40d") && (c.properties & 8) != 0)
                .findFirst().orElseThrow().handle;
        int[] attributes = {output, output, vendor};
        String[] names = {"HID seven-byte GIP", "HID thirteen-byte GIP", "Vendor seven-byte GIP"};
        for (int i = 0; i < attributes.length; i++) {
            if (sequence(serial, names[i], attributes[i], i == 1, output)) {
                serial.send("DISCONNECT");
                try { serial.await("DISCONNECTED ", 10); }
                catch (IOException e) { if (!e.getMessage().startsWith("DISCONNECTED ")) throw e; }
                ensureConnected(serial);
                boolean repeated = sequence(serial, names[i], attributes[i], i == 1, output);
                serial.record("RECONNECT_REPEAT " + repeated);
                return repeated ? "Brightness change reported again after reconnecting:\n\n" + names[i]
                        : "The initial brightness change was not reproduced after reconnecting:\n\n" + names[i];
            }
        }
        serial.record("RESULT No observed brightness change for the tested commands");
        return "No brightness change was reported for any of the three tested commands.";
    }

    static void selfTest() throws Exception {
        for (String command : List.of("SERVICES 0001", "CHARS 0001 0007", "DESCS 001f 0020", "READ 000f 0000")) {
            if (!Serial.retryBusy(command, "ERROR SOFTDEVICE 00000011", 0)
                    || Serial.retryBusy(command, "ERROR SOFTDEVICE 00000011", 10)
                    || Serial.retryBusy(command, "ERROR SOFTDEVICE 00000008", 0)
                    || Serial.retryBusy(command, "Timed out waiting for READ", 0))
                throw new AssertionError("Incorrect bounded read-only busy retry");
        }
        for (String command : List.of("WRITE 002a 00", "WRITE_CMD 001a 00", "PAIR", "CONNECT 000000000000 0", "DISCONNECT"))
            if (Serial.retryBusy(command, "ERROR SOFTDEVICE 00000011", 0))
                throw new AssertionError("Retried a command outside discovery/reads");
        if (!HEX.formatHex(led(1, 0, false)).equals("0a200103000100")
                || !HEX.formatHex(led(255, 20, true)).equals("0a20ff03000114000000000000")
                || status("WRITE 010d 0022") != 0x010d || STOP_RUMBLE.length != 8)
            throw new AssertionError("Packet/status self-check failed");
        try { led(0, 20, false); throw new AssertionError("Accepted sequence zero"); }
        catch (IllegalArgumentException expected) { }
        try { requireSuccess("WRITE 010d 0022"); throw new AssertionError("Accepted ATT failure"); }
        catch (IOException expected) { }
        if (!controlPointCommand(0x1a, 0).equals("WRITE_CMD 001a 00")
                || !controlPointCommand(0x1a, 1).equals("WRITE_CMD 001a 01"))
            throw new AssertionError("Incorrect HID Suspend/Resume encoding");
        try { controlPointCommand(0x1a, 2); throw new AssertionError("Accepted reserved HID control value"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("XboxBleExperiment self-test passed");
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length < 2 || !Set.of("--inspect", "--run", "--suspend-test", "--command").contains(args[0])
                || (!args[0].equals("--command") && args.length != 2) || (args[0].equals("--command") && args.length < 3))
            throw new IllegalArgumentException("Usage: XboxBleExperiment.java --self-test | --inspect PORT | --run PORT | --suspend-test PORT | --command PORT COMMAND...");
        String outcome = null;
        try (Serial serial = new Serial(args[1])) {
            if (!serial.last("PING", "PONG ").equals("PONG XboxBleProbe 1")) throw new IOException("Unexpected board firmware");
            if (args[0].equals("--command")) {
                String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                String op = args[2];
                String prefix = switch (op) {
                    case "PING" -> "PONG ";
                    case "DISCONNECT" -> "DISCONNECTED ";
                    case "SERVICES", "CHARS", "DESCS" -> "DONE " + op + " ";
                    case "SCAN", "STOP", "PAIR", "FORGET" -> "OK " + op;
                    case "CONNECT" -> "CONNECTED ";
                    case "WRITE_CMD" -> "SENT ";
                    default -> op + " ";
                };
                serial.request(command, prefix);
            } else {
                try { outcome = run(serial, args[0]); }
                finally {
                    serial.send("DISCONNECT");
                    NativeMacDialog.pause(300);
                    NativeMacDialog.hideProgress();
                }
            }
        } catch (CancellationException cancelled) { System.out.println(cancelled.getMessage()); return; }
        catch (IOException failure) {
            if (!args[0].equals("--command")) NativeMacDialog.choose("Experiment stopped",
                    failure.getMessage() + "\n\nThe runner stopped. Details are in the private transcript.", "OK");
            throw failure;
        }
        if (outcome != null) NativeMacDialog.choose("Experiment complete", outcome
                + "\n\nThe runner requested Bluetooth disconnection. Details are in the private transcript.", "OK");
    }
}
