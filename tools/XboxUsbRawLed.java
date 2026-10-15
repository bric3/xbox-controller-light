//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import java.util.Arrays;
import java.util.HexFormat;

import static java.lang.foreign.ValueLayout.*;

// Raw USB experiment inspired by xone, xbledctl and xbox-controller-driver-macos.
class XboxUsbRawLed {
    static byte[] led(int brightness) {
        if (brightness < 0 || brightness > 47) throw new IllegalArgumentException("Use a brightness step from 0 to 47");
        return new byte[]{0x0a, 0x20, 2, 3, 0, (byte) (brightness == 0 ? 0 : 1), (byte) brightness};
    }

    static int glow(long milliseconds) {
        return (int) Math.round(10 * (1 - Math.cos(2 * Math.PI * (milliseconds % 3000) / 3000.0)));
    }

    static void configuration(byte[] bytes) {
        if (bytes.length < 9 || bytes[0] != 9 || bytes[1] != 2
                || (Byte.toUnsignedInt(bytes[2]) | Byte.toUnsignedInt(bytes[3]) << 8) != bytes.length)
            throw new IllegalArgumentException("Invalid USB configuration descriptor");
        boolean gamepad = false, found = false, input = false, output = false;
        for (int offset = 0; offset < bytes.length;) {
            int length = Byte.toUnsignedInt(bytes[offset]);
            if (length < 2 || offset + length > bytes.length) throw new IllegalArgumentException("Invalid USB descriptor length");
            int type = Byte.toUnsignedInt(bytes[offset + 1]);
            if (type == 4) {
                if (length < 9) throw new IllegalArgumentException("Short interface descriptor");
                gamepad = bytes[offset + 2] == 0 && bytes[offset + 3] == 0
                        && bytes[offset + 5] == (byte) 0xff && bytes[offset + 6] == 0x47 && bytes[offset + 7] == (byte) 0xd0;
                found |= gamepad;
            } else if (type == 5 && gamepad) {
                if (length < 7 || (bytes[offset + 3] & 3) != 3
                        || (Byte.toUnsignedInt(bytes[offset + 4]) | Byte.toUnsignedInt(bytes[offset + 5]) << 8) != 64)
                    throw new IllegalArgumentException("Unexpected GIP interrupt endpoint");
                output |= bytes[offset + 2] == 2;
                input |= bytes[offset + 2] == (byte) 0x82;
            }
            offset += length;
        }
        if (!found || !input || !output) throw new IllegalArgumentException("Expected interface 0 ff/47/d0 with interrupt endpoints 02/82");
    }

    static void selfTest() {
        if (!HexFormat.of().formatHex(led(10)).equals("0a20020300010a") || led(0)[5] != 0 || led(47)[6] != 47)
            throw new AssertionError("Incorrect LED packet");
        for (int invalid : new int[]{-1, 48}) {
            try { led(invalid); throw new AssertionError("Accepted invalid intensity"); }
            catch (IllegalArgumentException expected) { }
        }
        if (glow(0) != 0 || glow(750) != 10 || glow(1500) != 20 || glow(3000) != 0)
            throw new AssertionError("Incorrect breathing brightness curve");
        for (long time = 0; time <= 6000; time += 37)
            if (glow(time) < 0 || glow(time) > 20) throw new AssertionError("Breathing brightness exceeded bounds");
        byte[] valid = HexFormat.of().parseHex("09022000010100a0fa0904000002ff47d0000705020340000407058203400004");
        configuration(valid);
        for (int offset : new int[]{0, 2, 9, 14, 20, 21, 23, 27}) {
            byte[] invalid = valid.clone();
            invalid[offset] ^= 1;
            try { configuration(invalid); throw new AssertionError("Accepted invalid configuration at " + offset); }
            catch (IllegalArgumentException expected) { }
        }
        try { configuration(Arrays.copyOf(valid, 30)); throw new AssertionError("Accepted truncated descriptor"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Verified LED packets, intensity bounds, breathing curve and rejection of nine malformed or incompatible configurations.");
    }

    static final Linker LINKER = Linker.nativeLinker();
    static Arena arena;
    static SymbolLookup usb;

    static Object call(String name, FunctionDescriptor signature, Object... args) throws Throwable {
        return LINKER.downcallHandle(usb.find(name).orElseThrow(), signature).invokeWithArguments(args);
    }

    static void check(int status, String operation) throws Throwable {
        if (status == 0) return;
        MemorySegment name = (MemorySegment) call("libusb_error_name", FunctionDescriptor.of(ADDRESS, JAVA_INT), status);
        throw new IllegalStateException(operation + ": " + name.reinterpret(128).getString(0));
    }

    static void send(MemorySegment handle, byte[] bytes) throws Throwable {
        MemorySegment transferred = arena.allocate(JAVA_INT);
        check((int) call("libusb_interrupt_transfer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
                handle, (byte) 2, arena.allocateFrom(JAVA_BYTE, bytes), bytes.length, transferred, 2000), "Interrupt OUT 02");
        if (transferred.get(JAVA_INT, 0) != bytes.length) throw new IllegalStateException("Short interrupt OUT transfer");
    }

    static void operate(MemorySegment handle, byte[] packet, boolean breathing) throws Throwable {
        var data = arena.allocate(4096);
        int length = (int) call("libusb_control_transfer", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_SHORT, JAVA_SHORT, ADDRESS, JAVA_SHORT, JAVA_INT),
                handle, (byte) 0x80, (byte) 6, (short) 0x0200, (short) 0, data, (short) 4096, 2000);
        if (length < 0) check(length, "Read configuration");
        configuration(data.asSlice(0, length).toArray(JAVA_BYTE));
        System.out.println("Verified interface 0 and 64-byte interrupt endpoints 02/82.");
        if (packet == null) return;

        boolean detached = false, claimed = false;
        try {
            int active = (int) call("libusb_kernel_driver_active", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), handle, 0);
            if (active < 0) check(active, "Check driver ownership");
            if (active == 1) {
                check((int) call("libusb_detach_kernel_driver", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), handle, 0), "Temporarily capture USB controller");
                detached = true;
                System.out.println("Captured controller; system input is temporarily unavailable.");
            }
            check((int) call("libusb_claim_interface", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), handle, 0), "Claim GIP interface 0");
            claimed = true;
            send(handle, new byte[]{5, 0x20, 1, 1, 0});
            System.out.println("Transferred START: 05 20 01 01 00");
            Thread.sleep(50);
            if (breathing) {
                System.out.println("Breathing LED for 30 seconds: three-second cycles, intensity 0-20, updates every 50 ms.");
                long start = System.nanoTime();
                int sequence = 2, writes = 0;
                while (System.nanoTime() - start < 30_000_000_000L) {
                    byte[] frame = led(glow((System.nanoTime() - start) / 1_000_000));
                    frame[2] = (byte) sequence;
                    send(handle, frame);
                    sequence = sequence == 255 ? 1 : sequence + 1;
                    writes++;
                    Thread.sleep(50);
                }
                System.out.println("Completed " + writes + " LED brightness transfers; visual confirmation is required.");
            } else {
                send(handle, packet);
                System.out.println("Transferred LED: " + HexFormat.ofDelimiter(" ").formatHex(packet));
                System.out.println("Observe the Xbox button for 30 seconds; transfer success alone does not confirm its effect.");
                Thread.sleep(30000);
            }
        } finally {
            if (claimed) {
                int status = (int) call("libusb_release_interface", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), handle, 0);
                System.out.println("Release interface status: " + status);
            }
            if (detached) {
                int status = (int) call("libusb_attach_kernel_driver", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), handle, 0);
                System.out.println("Restore driver matching status: " + status + "; reinitialisation may restore default brightness.");
                check(status, "Restore controller driver");
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) throw new IllegalArgumentException("Usage: XboxUsbRawLed.java STEP | --glow | --inspect | --self-test");
        if (args[0].equals("--self-test")) { selfTest(); return; }
        boolean breathing = args[0].equals("--glow");
        byte[] packet = args[0].equals("--inspect") ? null : led(breathing ? 0 : Integer.parseInt(args[0]));
        try (var resources = Arena.ofConfined()) {
            arena = resources;
            usb = SymbolLookup.libraryLookup("/opt/homebrew/lib/libusb-1.0.dylib", arena);
            var contextOut = arena.allocate(ADDRESS);
            check((int) call("libusb_init", FunctionDescriptor.of(JAVA_INT, ADDRESS), contextOut), "Initialise libusb");
            MemorySegment context = contextOut.get(ADDRESS, 0);
            try {
                var listOut = arena.allocate(ADDRESS);
                long count = (long) call("libusb_get_device_list", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS), context, listOut);
                if (count < 0) check((int) count, "Enumerate USB");
                MemorySegment list = listOut.get(ADDRESS, 0).reinterpret((count + 1) * ADDRESS.byteSize());
                try {
                    MemorySegment selected = MemorySegment.NULL;
                    int matches = 0;
                    for (long i = 0; i < count; i++) {
                        MemorySegment device = list.getAtIndex(ADDRESS, i);
                        MemorySegment descriptor = arena.allocate(18, 2);
                        check((int) call("libusb_get_device_descriptor", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), device, descriptor), "Read USB identity");
                        if (Short.toUnsignedInt(descriptor.get(JAVA_SHORT, 8)) == 0x045e
                                && Short.toUnsignedInt(descriptor.get(JAVA_SHORT, 10)) == 0x0b12) { selected = device; matches++; }
                    }
                    if (matches != 1) throw new IllegalStateException("Expected exactly one USB 045e:0b12 controller, found " + matches);
                    var handleOut = arena.allocate(ADDRESS);
                    check((int) call("libusb_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), selected, handleOut), "Open controller");
                    MemorySegment handle = handleOut.get(ADDRESS, 0);
                    try { operate(handle, packet, breathing); }
                    finally { call("libusb_close", FunctionDescriptor.ofVoid(ADDRESS), handle); }
                } finally { call("libusb_free_device_list", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), list, 1); }
            } finally { call("libusb_exit", FunctionDescriptor.ofVoid(ADDRESS), context); }
        }
    }
}
