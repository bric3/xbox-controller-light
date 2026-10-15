//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;

class XboxUsbLed {
    static final Linker LINKER = Linker.nativeLinker();
    static Arena ARENA;
    static SymbolLookup SYMBOLS;

    enum Operation { HELP, SET, INCREMENT, DECREMENT, GLOW, INSPECT, SELF_TEST }
    enum Transport { USB, BLUETOOTH }
    record Command(Operation operation, int value) { }

    static final String HELP = """
            Usage: jbang tools/XboxUsbLed.java [command]

              -h, --help       Show this help (also shown with no arguments)
              --on             Set steady brightness to step 20
              --off            Switch the light off
              --brightness N   Set an integer brightness step, 0-47
              --increment [N]  Increase the remembered step by N (default 1; range 1-47)
              --decrement [N]  Decrease the remembered step by N (default 1; range 1-47)
              N                Set brightness, equivalent to --brightness N
              --glow           Breathe between steps 0-20 for 30 seconds, ending off
              --inspect        Detect one matching USB/Bluetooth controller without writing
              --self-test      Check arguments, transport selection, packets and saved state

            Use exactly one command. Values outside 0-47 are rejected.
            --brightness sets an absolute step; brightness readback is unavailable.
            Relative changes use this tool's last value for the current USB connection.
            Run --on or --brightness N first after reconnecting or restarting the Mac.
            Changes made by other software cannot be detected. State is cached under
            ~/Library/Caches/xbox-light/.

            Automatically detects Series X|S USB 045e:0b12 or Bluetooth 045e:0b13.
            LED control works over USB on macOS. Bluetooth uses a separate path and
            reports unsupported LED control; it never sends USB packets or changes
            the USB brightness cache. No administrator authentication is required.
            """;

    static int integer(String value, String label, int minimum, int maximum) {
        try {
            int result = Integer.parseInt(value);
            if (result >= minimum && result <= maximum) return result;
        } catch (NumberFormatException invalid) {
            // Report syntax and range errors consistently before opening a device.
        }
        throw new IllegalArgumentException(label + " must be an integer from " + minimum + " to " + maximum + "; got '" + value + "'");
    }

    static Command parse(String[] args) {
        if (args.length == 0) return new Command(Operation.HELP, 0);
        Operation operation = switch (args[0]) {
            case "-h", "--help" -> Operation.HELP;
            case "--on", "--off", "--brightness" -> Operation.SET;
            case "--increment" -> Operation.INCREMENT;
            case "--decrement" -> Operation.DECREMENT;
            case "--glow" -> Operation.GLOW;
            case "--inspect" -> Operation.INSPECT;
            case "--self-test" -> Operation.SELF_TEST;
            default -> {
                if (args[0].startsWith("-") && !args[0].matches("-\\d+"))
                    throw new IllegalArgumentException("Unknown option: " + args[0]);
                yield Operation.SET;
            }
        };
        boolean parameter = args[0].equals("--brightness");
        boolean relative = operation == Operation.INCREMENT || operation == Operation.DECREMENT;
        if (relative ? args.length > 2 : args.length != (parameter ? 2 : 1))
            throw new IllegalArgumentException(parameter && args.length == 1
                    ? args[0] + " requires an integer value" : "Use exactly one command with its required value");
        int value = switch (operation) {
            case INCREMENT, DECREMENT -> args.length == 1 ? 1 : integer(args[1], "Step size", 1, 47);
            case SET -> switch (args[0]) {
                case "--on" -> 20;
                case "--off" -> 0;
                default -> integer(args[parameter ? 1 : 0], "Brightness", 0, 47);
            };
            default -> 0;
        };
        return new Command(operation, value);
    }

    static int adjust(int brightness, int delta) {
        int result = brightness + delta;
        if (result < 0 || result > 47)
            throw new IllegalArgumentException("Brightness " + brightness + " with change " + delta + " would exceed the 0-47 range");
        return result;
    }

    static int remembered(Path path, String connection) throws IOException {
        if (!Files.exists(path)) throw new IllegalArgumentException("No remembered brightness; run --on or --brightness N first");
        String[] fields = Files.readString(path).strip().split("\\s+");
        if (fields.length != 2)
            throw new IllegalArgumentException("Invalid saved brightness; run --on or --brightness N to replace it");
        if (!fields[0].equals(connection))
            throw new IllegalArgumentException("The USB connection changed; run --on or --brightness N to establish its brightness");
        return integer(fields[1], "Saved brightness", 0, 47);
    }

    static void remember(Path path, String connection, int brightness) throws IOException {
        integer(Integer.toString(brightness), "Brightness", 0, 47);
        Path temporary = Files.createTempFile(path.getParent(), "usb-led-", ".tmp");
        try {
            Files.writeString(temporary, connection + " " + brightness + "\n");
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String connection(MemorySegment device) throws Throwable {
        int service = (int) call("IOHIDDeviceGetService", FunctionDescriptor.of(JAVA_INT, ADDRESS), device);
        var identity = ARENA.allocate(JAVA_LONG);
        check((int) call("IORegistryEntryGetRegistryEntryID", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), service, identity),
                "Read USB connection identity");
        // Registry IDs can be reused after a reboot; include the kernel's boot-session UUID.
        var boot = ARENA.allocate(64);
        var size = ARENA.allocateFrom(JAVA_LONG, boot.byteSize());
        int status = (int) call("sysctlbyname", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG),
                ARENA.allocateFrom("kern.bootsessionuuid"), boot, size, MemorySegment.NULL, 0L);
        if (status != 0) throw new IllegalStateException("Could not identify the current macOS boot session");
        return boot.getString(0) + ":" + Long.toUnsignedString(identity.get(JAVA_LONG, 0));
    }

    static Object call(String name, FunctionDescriptor signature, Object... args) throws Throwable {
        return LINKER.downcallHandle(SYMBOLS.find(name).orElseThrow(), signature).invokeWithArguments(args);
    }

    static void release(MemorySegment value) throws Throwable {
        if (value.address() != 0) call("CFRelease", FunctionDescriptor.ofVoid(ADDRESS), value);
    }

    static MemorySegment string(String value) throws Throwable {
        return (MemorySegment) call("CFStringCreateWithCString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                MemorySegment.NULL, ARENA.allocateFrom(value), 0x08000100);
    }

    static void match(MemorySegment dictionary, String name, Object value) throws Throwable {
        MemorySegment key = string(name);
        MemorySegment item = value instanceof String text ? string(text) :
                (MemorySegment) call("CFNumberCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
                        MemorySegment.NULL, 3, ARENA.allocateFrom(JAVA_INT, (int) value));
        try {
            call("CFDictionarySetValue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), dictionary, key, item);
        } finally {
            release(key);
            release(item);
        }
    }

    static MemorySegment property(MemorySegment device, String name) throws Throwable {
        MemorySegment key = string(name);
        try {
            return (MemorySegment) call("IOHIDDeviceGetProperty", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), device, key);
        } finally { release(key); }
    }

    static int productId(MemorySegment device) throws Throwable {
        MemorySegment value = property(device, "ProductID");
        if (value.address() == 0) return -1;
        var result = ARENA.allocate(JAVA_INT);
        byte success = (byte) call("CFNumberGetValue", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_INT, ADDRESS), value, 3, result);
        return success == 0 ? -1 : result.get(JAVA_INT, 0);
    }

    static String transportName(MemorySegment device) throws Throwable {
        MemorySegment value = property(device, "Transport");
        if (value.address() == 0) return "";
        var result = ARENA.allocate(256);
        byte success = (byte) call("CFStringGetCString", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT),
                value, result, result.byteSize(), 0x08000100);
        return success == 0 ? "" : result.getString(0);
    }

    static Transport transport(int productId, String name) {
        if (productId == 0x0b12 && name.equals("USB")) return Transport.USB;
        if (productId == 0x0b13 && (name.equals("Bluetooth") || name.equals("Bluetooth Low Energy"))) return Transport.BLUETOOTH;
        return null;
    }

    static void applyBluetooth() {
        throw new UnsupportedOperationException("Bluetooth controller detected, but no working Bluetooth LED command is known. "
                + "Brightness and glow are unavailable over Bluetooth; connect by USB for LED control.");
    }

    static byte[] packet(int brightness, int sequence) {
        if (brightness < 0 || brightness > 47) throw new IllegalArgumentException("Use a brightness step from 0 to 47");
        if (sequence < 1 || sequence > 255) throw new IllegalArgumentException("Use a sequence from 1 to 255");
        // Apple's inherited XboxWirelessGamepad handler only forwards output ID 1 with exactly 13 bytes.
        return new byte[]{0x0a, 0x20, (byte) sequence, 3, 0, (byte) (brightness == 0 ? 0 : 1), (byte) brightness,
                0, 0, 0, 0, 0, 0};
    }

    static int glow(long milliseconds) {
        return (int) Math.round(10 * (1 - Math.cos(2 * Math.PI * (milliseconds % 3000) / 3000.0)));
    }

    static int nextSequence(int sequence) {
        return sequence == 255 ? 1 : sequence + 1;
    }

    static void check(int status, String operation) {
        if (status != 0) throw new IllegalStateException("%s: IOReturn 0x%08x".formatted(operation, status));
    }

    static void send(MemorySegment device, int brightness, int sequence) throws Throwable {
        byte[] bytes = packet(brightness, sequence);
        check((int) call("IOHIDDeviceSetReport", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG),
                device, 1, 1L, ARENA.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length), "Send LED output report");
    }

    static void selfTest() throws Exception {
        if (transport(0x0b12, "USB") != Transport.USB
                || transport(0x0b13, "Bluetooth Low Energy") != Transport.BLUETOOTH
                || transport(0x0b13, "Bluetooth") != Transport.BLUETOOTH
                || transport(0x0b13, "USB") != null || transport(0x0b12, "Bluetooth Low Energy") != null
                || transport(0x9999, "USB") != null || transport(0x0b13, "") != null)
            throw new AssertionError("Incorrect transport selection");
        try { applyBluetooth(); throw new AssertionError("Reported unverified Bluetooth LED support"); }
        catch (UnsupportedOperationException expected) { }
        if (!HexFormat.of().formatHex(packet(10, 1)).equals("0a20010300010a000000000000")
                || packet(0, 1)[5] != 0 || packet(47, 255)[6] != 47
                || nextSequence(254) != 255 || nextSequence(255) != 1)
            throw new AssertionError("LED framing self-test failed");
        for (int[] invalid : new int[][]{{-1, 1}, {48, 1}, {10, 0}, {10, 256}}) {
            try { packet(invalid[0], invalid[1]); throw new AssertionError("Accepted invalid LED packet"); }
            catch (IllegalArgumentException expected) { }
        }
        if (glow(0) != 0 || glow(750) != 10 || glow(1500) != 20 || glow(3000) != 0)
            throw new AssertionError("Incorrect breathing brightness curve");
        for (long time = 0; time <= 6000; time += 37)
            if (glow(time) < 0 || glow(time) > 20) throw new AssertionError("Breathing brightness exceeded bounds");

        if (!parse(new String[0]).equals(new Command(Operation.HELP, 0))
                || !parse(new String[]{"-h"}).equals(new Command(Operation.HELP, 0))
                || !parse(new String[]{"--help"}).equals(new Command(Operation.HELP, 0))
                || !parse(new String[]{"--on"}).equals(new Command(Operation.SET, 20))
                || !parse(new String[]{"--off"}).equals(new Command(Operation.SET, 0))
                || !parse(new String[]{"--brightness", "47"}).equals(new Command(Operation.SET, 47))
                || !parse(new String[]{"10"}).equals(new Command(Operation.SET, 10))
                || !parse(new String[]{"--brightness", "0"}).equals(new Command(Operation.SET, 0))
                || !parse(new String[]{"--increment"}).equals(new Command(Operation.INCREMENT, 1))
                || !parse(new String[]{"--decrement"}).equals(new Command(Operation.DECREMENT, 1))
                || !parse(new String[]{"--increment", "5"}).equals(new Command(Operation.INCREMENT, 5))
                || !parse(new String[]{"--decrement", "47"}).equals(new Command(Operation.DECREMENT, 47))
                || parse(new String[]{"--glow"}).operation() != Operation.GLOW
                || parse(new String[]{"--inspect"}).operation() != Operation.INSPECT
                || parse(new String[]{"--self-test"}).operation() != Operation.SELF_TEST)
            throw new AssertionError("Incorrect command parsing");
        for (String[] invalid : new String[][]{{"--unknown"}, {"--brightness"},
                {"--brightness", "-1"}, {"--brightness", "48"}, {"--brightness", "1.5"}, {"--brightness", "abc"},
                {"--brightness", "2147483648"}, {"--increment", "0"}, {"--increment", "-1"}, {"--decrement", "48"},
                {"--decrement", "1.5"}, {"--increment", "1", "2"}, {"--on", "--off"}, {"--off", "10"}, {"--help", "--on"}, {"10", "20"},
                {"--glow", "--on"}, {"-1"}, {"48"}, {"1.5"}}) {
            try { parse(invalid); throw new AssertionError("Accepted invalid arguments: " + String.join(" ", invalid)); }
            catch (IllegalArgumentException expected) { }
        }
        if (adjust(20, 27) != 47 || adjust(20, -20) != 0)
            throw new AssertionError("Incorrect relative brightness calculation");
        for (int[] invalid : new int[][]{{0, -1}, {47, 1}, {20, 28}, {20, -21}}) {
            try { adjust(invalid[0], invalid[1]); throw new AssertionError("Accepted out-of-range brightness change"); }
            catch (IllegalArgumentException expected) { }
        }
        Path directory = Files.createTempDirectory("xbox-led-test-");
        Path state = directory.resolve("state");
        try {
            try { remembered(state, "boot:101"); throw new AssertionError("Accepted missing brightness state"); }
            catch (IllegalArgumentException expected) { }
            remember(state, "boot:101", 20);
            if (remembered(state, "boot:101") != 20) throw new AssertionError("Remembered brightness did not round-trip");
            remember(state, "boot:101", 0);
            if (remembered(state, "boot:101") != 0) throw new AssertionError("Could not remember off");
            for (String invalid : List.of("bad", "boot:102 20", "oldboot:101 20", "boot:101 48",
                    "boot:101 -1", "boot:101 1.5", "boot:101 2147483648")) {
                Files.writeString(state, invalid);
                try { remembered(state, "boot:101"); throw new AssertionError("Accepted invalid or stale brightness state"); }
                catch (IllegalArgumentException expected) { }
            }
        } finally {
            Files.deleteIfExists(state);
            Files.delete(directory);
        }
        System.out.println("Verified arguments, transport selection, Bluetooth rejection, relative bounds, saved state, LED framing and breathing; no device access.");
    }

    static void apply(MemorySegment device, Command command) throws Throwable {
        String connection = connection(device);
        Path state = Path.of(System.getProperty("user.home"), "Library", "Caches", "xbox-light", "usb-led.state");
        Files.createDirectories(state.getParent());
        try (var channel = FileChannel.open(state.resolveSibling("usb-led.lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another LED command is running; retry after it finishes");
            int brightness = switch (command.operation()) {
                case INCREMENT -> adjust(remembered(state, connection), command.value());
                case DECREMENT -> adjust(remembered(state, connection), -command.value());
                default -> command.value();
            };
            // An interrupted command must not leave a trusted baseline for later relative changes.
            Files.deleteIfExists(state);
            if (command.operation() == Operation.GLOW) {
                System.out.println("Breathing for 30 seconds: three-second cycles, brightness steps 0-20; finishing off.");
                long start = System.nanoTime();
                int sequence = 1, writes = 0;
                try {
                    while (System.nanoTime() - start < 30_000_000_000L) {
                        send(device, glow((System.nanoTime() - start) / 1_000_000), sequence);
                        sequence = nextSequence(sequence);
                        writes++;
                        Thread.sleep(50);
                    }
                } finally { send(device, 0, sequence); }
                System.out.println("Completed " + writes + " brightness updates and switched the LED off.");
            } else {
                send(device, brightness, 1);
                System.out.println("Sent brightness step " + brightness + " through shared HID output ID 1 with a 13-byte buffer.");
            }
            try { remember(state, connection, brightness); }
            catch (IOException failure) {
                throw new IOException("LED command was accepted, but its brightness could not be saved: " + failure.getMessage(), failure);
            }
        }
    }

    static void control(Command command) throws Throwable {
        MemorySegment manager = (MemorySegment) call("IOHIDManagerCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), MemorySegment.NULL, 0);
        if (manager.address() == 0) throw new IllegalStateException("Could not create HID manager");
        MemorySegment devices = MemorySegment.NULL;
        boolean opened = false;
        try {
            MemorySegment dictionary = (MemorySegment) call("CFDictionaryCreateMutable",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS), MemorySegment.NULL, 0L,
                    SYMBOLS.find("kCFTypeDictionaryKeyCallBacks").orElseThrow(), SYMBOLS.find("kCFTypeDictionaryValueCallBacks").orElseThrow());
            try {
                match(dictionary, "VendorID", 0x045e);
                match(dictionary, "PrimaryUsagePage", 1);
                match(dictionary, "PrimaryUsage", 5);
                call("IOHIDManagerSetDeviceMatching", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), manager, dictionary);
            } finally {
                release(dictionary);
            }
            check((int) call("IOHIDManagerOpen", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), manager, 0), "Open shared HID connection");
            opened = true;
            devices = (MemorySegment) call("IOHIDManagerCopyDevices", FunctionDescriptor.of(ADDRESS, ADDRESS), manager);
            long count = devices.address() == 0 ? 0 : (long) call("CFSetGetCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS), devices);
            MemorySegment selected = MemorySegment.NULL;
            Transport selectedTransport = null;
            int matching = 0;
            if (count > 0) {
                var values = ARENA.allocate(ADDRESS, count);
                call("CFSetGetValues", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), devices, values);
                for (long index = 0; index < count; index++) {
                    MemorySegment device = values.getAtIndex(ADDRESS, index);
                    Transport candidate = transport(productId(device), transportName(device));
                    if (candidate != null) {
                        selected = device;
                        selectedTransport = candidate;
                        matching++;
                    }
                }
            }
            if (matching != 1) throw new IllegalStateException("Expected one Series X|S controller over USB 045e:0b12 or Bluetooth 045e:0b13, found "
                    + matching + ". Connect exactly one matching controller.");
            if (selectedTransport == Transport.USB) {
                System.out.println("Found exactly one USB 045e:0b12 controller through shared HID access.");
                if (command.operation() != Operation.INSPECT) apply(selected, command);
            } else {
                System.out.println("Found exactly one Bluetooth 045e:0b13 controller through shared HID access; LED control is unavailable.");
                if (command.operation() != Operation.INSPECT) applyBluetooth();
            }
        } finally {
            release(devices);
            if (opened) call("IOHIDManagerClose", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), manager, 0);
            release(manager);
        }
    }

    public static void main(String[] args) throws Throwable {
        try {
            Command command = parse(args);
            if (command.operation() == Operation.HELP) { System.out.print(HELP); return; }
            if (command.operation() == Operation.SELF_TEST) { selfTest(); return; }
            try (var resources = Arena.ofConfined()) {
                ARENA = resources;
                SYMBOLS = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", ARENA)
                        .or(SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", ARENA))
                        .or(LINKER.defaultLookup());
                control(command);
            }
        } catch (IllegalArgumentException invalid) {
            System.err.println("Error: " + invalid.getMessage() + "\nUse --help for usage.");
            System.exit(2);
        } catch (Exception failure) {
            System.err.println("Error: " + failure.getMessage());
            System.exit(1);
        }
    }
}
