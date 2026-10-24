//SOURCES XboxBleExperiment.java XboxUsbRawLed.java XboxUsbLed.java
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import static java.lang.foreign.ValueLayout.*;

/** Guided runtime experiments and read-only configuration inspection. */
class XboxBrightnessCampaign {
    static final HexFormat HEX = HexFormat.of();
    static final int[] KEYS = {0x51db, 0xff6e, 0xabaa};
    static final int[] STEPS = {0, 20, 0, 20};
    static PrintWriter transcript;
    static Path directory;
    static boolean factoryAttempted;
    static final long APPLICATION = 0x10027000L;
    static final int APPLICATION_SIZE = 0x7e000;

    record Command(int selector, int message, byte[] data) {
        Command {
            data = data.clone();
            boolean led = selector == 11 && message == 0xb01 && data.length == 1
                    && Byte.toUnsignedInt(data[0]) <= 20;
            boolean unlock = selector == 2 && message >= 0x20f && message <= 0x211
                    && data.length == 2 && Short.toUnsignedInt(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort()) == KEYS[message - 0x20f];
            boolean lookup = selector == 3 && message == 0x308 && Arrays.equals(data, new byte[2]);
            boolean read = selector == 3 && message == 0x300 && Arrays.equals(data, new byte[]{0x60, 0, 32});
            boolean flash = false;
            if (selector == 22 && message == 0x1601 && data.length == 5) {
                long address = Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt());
                int length = Byte.toUnsignedInt(data[4]);
                flash = length >= 1 && length <= 48 && address >= APPLICATION
                        && address + length <= APPLICATION + APPLICATION_SIZE;
            }
            if (!led && !unlock && !lookup && !read && !flash)
                throw new IllegalArgumentException("Campaign command is outside the runtime/read-only whitelist");
        }

        byte[] frame() {
            return ByteBuffer.allocate(6 + data.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte)0).put((byte)selector).putShort((short)message).putShort((short)data.length).put(data).array();
        }

        int usbValue() { return selector << 8 | (selector == 3 ? 0x0e : 0x0c); }
    }

    static Command led(int step) {
        if (step < 0 || step > 20) throw new IllegalArgumentException("Campaign brightness must be 0-20");
        return new Command(11, 0xb01, new byte[]{(byte)step});
    }
    static Command unlock(int index) {
        return new Command(2, 0x20f + index, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)KEYS[index]).array());
    }

    static byte[] wrapped(Command command, int sequence) {
        if (sequence < 1 || sequence > 255) throw new IllegalArgumentException("Invalid GIP sequence");
        byte[] frame = command.frame();
        return ByteBuffer.allocate(frame.length + 5).put((byte)0x1f).put((byte)0).put((byte)sequence)
                .put((byte)(frame.length + 1)).put((byte)2).put(frame).array();
    }

    static void record(String text) throws IOException {
        transcript.println(Instant.now() + " " + text); transcript.flush();
        if (transcript.checkError()) throw new IOException("Cannot save campaign transcript");
        System.out.println(text);
    }

    static int observe(String name) throws Throwable {
        int answer = NativeMacDialog.choose("Record the light: " + name,
                "Did the Xbox light alternate off and steady with the four requests?\n\n"
                + "Pairing flashes and disconnects do not count. Choose Didn't see to repeat the trial.",
                "Changed", "Unchanged", "Didn't see", "Cancel");
        if (answer < 0 || answer > 2) throw new CancellationException("Observation cancelled");
        record("OBSERVATION " + name + " " + List.of("changed", "unchanged", "not-observed").get(answer));
        return answer;
    }

    static void ready(String name, String detail) throws Throwable {
        NativeMacDialog.proceed(name, detail + "\n\n1. Watch the Xbox light.\n\n"
                + "2. Click OK when ready. Four requests alternate off and steady, three seconds apart.\n\n"
                + "3. Record the result in the next dialog. There is no time limit on the dialogs.");
    }

    static String connected(XboxBleExperiment.Serial serial) throws Throwable {
        String status = serial.last("STATUS", "STATUS ");
        if (!status.startsWith("STATUS 1 1 ")) throw new IOException("Encrypted BLE connection lost: " + status);
        return status;
    }

    static void vendorWrite(XboxBleExperiment.Serial serial, int vendor, byte[] data) throws Throwable {
        String response = XboxBleExperiment.write(serial, vendor, data);
        record("BLE_WRITE " + HEX.formatHex(data) + " " + response);
    }

    static boolean bleTrial(XboxBleExperiment.Serial serial, int vendor, String name, boolean gip, boolean unlock) throws Throwable {
        while (true) {
            ready(name, "XIAO vendor-characteristic trial. This is a hypothesis for the installed firmware."
                    + (unlock ? "\n\nThe three volatile factory keys are sent first." : ""));
            XboxBleExperiment.ensureConnected(serial);
            record("BEFORE " + name + " " + connected(serial));
            try {
                if (unlock) for (int i = 0; i < KEYS.length; i++) {
                    factoryAttempted = true;
                    byte[] frame = unlock(i).frame();
                    vendorWrite(serial, vendor, gip ? wrapped(unlock(i), i + 1) : frame);
                    NativeMacDialog.pause(100);
                }
                for (int i = 0; i < STEPS.length; i++) {
                    NativeMacDialog.progress(name, "Request " + (i + 1) + " of 4: brightness " + STEPS[i] + ".\n\nWatch the Xbox light.");
                    vendorWrite(serial, vendor, gip ? wrapped(led(STEPS[i]), i + 4) : led(STEPS[i]).frame());
                    NativeMacDialog.pause(3000);
                    connected(serial);
                }
            } finally {
                try {
                    if (serial.last("STATUS", "STATUS ").startsWith("STATUS 1 1 "))
                        vendorWrite(serial, vendor, gip ? wrapped(led(20), 8) : led(20).frame());
                } catch (Throwable cleanup) { record("BLE_LED_CLEANUP_FAILED " + cleanup); }
            }
            record("AFTER " + name + " " + connected(serial));
            int answer = observe(name);
            if (answer != 2) return answer == 0;
        }
    }

    static boolean ble(String port) throws Throwable {
        try (var serial = new XboxBleExperiment.Serial(port)) {
            if (!serial.last("PING", "PONG ").equals("PONG XboxBleProbe 1")) throw new IOException("Unexpected XIAO firmware");
            try {
                XboxBleExperiment.run(serial, "--inspect");
                var schema = XboxBleExperiment.expectedSchema;
                var hid = Objects.requireNonNull(schema.get("1812"));
                int inputCcc = 0;
                for (var characteristic : hid) if (characteristic.uuid().equals("2a4d")) {
                    var descriptors = XboxBleExperiment.descriptors(serial, characteristic);
                    var reference = descriptors.stream().filter(d -> d.uuid().equals("2908")).findFirst().orElseThrow();
                    if (HEX.formatHex(XboxBleExperiment.read(serial, reference.handle())).equals("0101"))
                        inputCcc = descriptors.stream().filter(d -> d.uuid().equals("2902")).findFirst().orElseThrow().handle();
                }
                if (inputCcc == 0) throw new IOException("Missing HID input CCC");
                while (true) {
                    XboxBleExperiment.ensureConnected(serial);
                    XboxBleExperiment.requireSuccess(XboxBleExperiment.write(serial, inputCcc, new byte[]{1, 0}));
                    long before = Long.parseLong(connected(serial).split(" ")[3]);
                    NativeMacDialog.proceed("Check XIAO input", "1. Move a controller stick or press a button.\n\n2. Click OK to verify fresh input before testing.\n\nIf the controller sleeps, setup resumes after this dialog.");
                    String status = serial.last("STATUS", "STATUS ");
                    if (status.startsWith("STATUS 1 1 ") && Long.parseLong(status.split(" ")[3]) > before) break;
                }
                int vendor = Objects.requireNonNull(schema.get(XboxBleExperiment.VENDOR)).stream()
                        .filter(c -> c.uuid().equals("000000045f604c4f9c83a7953298d40d") && (c.properties() & 8) != 0)
                        .findFirst().orElseThrow().handle();
                String[] names = {"BLE raw factory LED", "BLE GIP factory LED", "BLE raw factory unlock + LED", "BLE GIP factory unlock + LED"};
                for (int i = 0; i < names.length; i++) {
                    boolean gip = (i & 1) != 0, unlock = i >= 2;
                    if (bleTrial(serial, vendor, names[i], gip, unlock)) {
                        serial.request("DISCONNECT", "DISCONNECTED ");
                        XboxBleExperiment.ensureConnected(serial);
                        boolean repeated = bleTrial(serial, vendor, names[i] + " after reconnect", gip, unlock);
                        record("BLE_RECONNECT_REPEAT " + names[i] + " " + repeated);
                        if (repeated) return true;
                    }
                }
                return false;
            } finally {
                try {
                    if (serial.last("STATUS", "STATUS ").startsWith("STATUS 1 ")) serial.request("DISCONNECT", "DISCONNECTED ");
                } catch (Throwable cleanup) { record("BLE_DISCONNECT_FAILED " + cleanup); }
                NativeMacDialog.hideProgress();
            }
        }
    }

    static final class Usb implements AutoCloseable {
        final Arena memory = Arena.ofConfined();
        MemorySegment context = MemorySegment.NULL, handle = MemorySegment.NULL;
        boolean trace = true;

        Usb() throws Throwable {
            XboxUsbRawLed.arena = memory;
            XboxUsbRawLed.usb = SymbolLookup.libraryLookup("/opt/homebrew/lib/libusb-1.0.dylib", memory);
            try {
                var output = memory.allocate(ADDRESS);
                XboxUsbRawLed.check((int)XboxUsbRawLed.call("libusb_init", FunctionDescriptor.of(JAVA_INT, ADDRESS), output), "Initialise USB");
                context = output.get(ADDRESS, 0);
                long count = (long)XboxUsbRawLed.call("libusb_get_device_list", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS), context, output);
                if (count < 0) XboxUsbRawLed.check((int)count, "Enumerate USB");
                MemorySegment list = output.get(ADDRESS, 0).reinterpret((count + 1) * ADDRESS.byteSize());
                try {
                    MemorySegment selected = MemorySegment.NULL;
                    int matches = 0;
                    for (long i = 0; i < count; i++) {
                        MemorySegment device = list.getAtIndex(ADDRESS, i), descriptor = memory.allocate(18, 2);
                        XboxUsbRawLed.check((int)XboxUsbRawLed.call("libusb_get_device_descriptor", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), device, descriptor), "USB identity");
                        if (Short.toUnsignedInt(descriptor.get(JAVA_SHORT, 8)) == 0x045e && Short.toUnsignedInt(descriptor.get(JAVA_SHORT, 10)) == 0x0b12) {
                            selected = device; matches++;
                        }
                    }
                    if (matches != 1) throw new IOException("Expected one USB 045e:0b12 controller; found " + matches);
                    XboxUsbRawLed.check((int)XboxUsbRawLed.call("libusb_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), selected, output), "Open controller USB");
                    handle = output.get(ADDRESS, 0);
                } finally { XboxUsbRawLed.call("libusb_free_device_list", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), list, 1); }
                byte[] config = transfer(0x80, 6, 0x200, 0, new byte[4096]);
                XboxUsbRawLed.configuration(config);
                record("USB_IDENTITY 045e:0b12 configuration verified; no interface claim or driver detachment");
            } catch (Throwable failure) { close(); throw failure; }
        }

        byte[] transfer(int type, int request, int value, int index, byte[] bytes) throws Throwable {
            var data = memory.allocateFrom(JAVA_BYTE, bytes);
            int length = (int)XboxUsbRawLed.call("libusb_control_transfer", FunctionDescriptor.of(JAVA_INT,
                            ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_SHORT, JAVA_SHORT, ADDRESS, JAVA_SHORT, JAVA_INT),
                    handle, (byte)type, (byte)request, (short)value, (short)index, data, (short)bytes.length, 2000);
            if (trace || length < 0) record("USB_CONTROL %02x %02x %04x %04x len=%d result=%d".formatted(type, request, value, index, bytes.length, length));
            if (length < 0) XboxUsbRawLed.check(length, "USB control request");
            if ((type & 0x80) == 0 && length != bytes.length) throw new IOException("Short USB OUT request");
            byte[] result = data.asSlice(0, length).toArray(JAVA_BYTE);
            if (trace) record("USB_DATA " + HEX.formatHex(result));
            return result;
        }

        void send(Command command) throws Throwable {
            transfer(0x40, 0x10, command.usbValue(), command.message(), command.data());
        }

        byte[] reply(int message) throws Throwable {
            long deadline = System.nanoTime() + 2_500_000_000L;
            boolean tracing = trace;
            byte[] previous = null;
            int polls = 0;
            try {
                trace = false;
                while (System.nanoTime() < deadline) {
                    byte[] bytes = transfer(0xc0, 0, 8, 0xff00, new byte[64]);
                    polls++;
                    if (tracing && !Arrays.equals(previous, bytes))
                        record("USB_REPLY_POLL c0 00 0008 ff00 len=64 command=" + Integer.toHexString(message)
                                + " poll=" + polls + " data=" + HEX.formatHex(bytes));
                    previous = bytes;
                    if (bytes.length >= 4) {
                        int status = Byte.toUnsignedInt(bytes[0]);
                        int actual = Byte.toUnsignedInt(bytes[1]) | Byte.toUnsignedInt(bytes[2]) << 8;
                        if (actual == message && status != 0xff && status != 2) {
                            if (status != 0) throw new IOException("Controller factory status " + status + " for " + Integer.toHexString(message));
                            return responsePayload(bytes, message);
                        }
                    }
                    NativeMacDialog.pause(5);
                }
            } finally { trace = tracing; }
            throw new IOException("No matching successful factory reply for " + Integer.toHexString(message));
        }

        @Override public void close() throws Exception {
            try {
                if (handle.address() != 0) XboxUsbRawLed.call("libusb_close", FunctionDescriptor.ofVoid(ADDRESS), handle);
                if (context.address() != 0) XboxUsbRawLed.call("libusb_exit", FunctionDescriptor.ofVoid(ADDRESS), context);
            } catch (Throwable failure) { throw new IOException(failure); }
            finally { memory.close(); }
        }
    }

    static byte[] responsePayload(byte[] response, int message) throws IOException {
        if (response.length < 4 || response[0] != 0 || Byte.toUnsignedInt(response[1]) != (message & 255)
                || Byte.toUnsignedInt(response[2]) != (message >>> 8) || Byte.toUnsignedInt(response[3]) > 56
                || response.length < 4 + Byte.toUnsignedInt(response[3])) throw new IOException("Invalid factory reply framing");
        return Arrays.copyOfRange(response, 4, 4 + Byte.toUnsignedInt(response[3]));
    }

    static int recordCap(byte[] bytes) throws IOException {
        if (bytes.length != 32 || bytes[0] != 0) throw new IOException("Expected 32-byte record-0 slot");
        int length = Byte.toUnsignedInt(bytes[1]);
        if (length < 26 || length > 29) throw new IOException("Unexpected record-0 payload length " + length);
        int sum = 0;
        for (int i = 2; i < 2 + length; i++) sum = (sum + Byte.toUnsignedInt(bytes[i])) & 255;
        if (sum != Byte.toUnsignedInt(bytes[2 + length])) throw new IOException("Record-0 checksum mismatch");
        return Byte.toUnsignedInt(bytes[24]);
    }

    static void hidLed(int step) throws Throwable {
        try (var memory = Arena.ofConfined()) {
            XboxUsbLed.ARENA = memory;
            XboxUsbLed.SYMBOLS = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", memory)
                    .or(SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", memory))
                    .or(XboxUsbLed.LINKER.defaultLookup());
            XboxUsbLed.control(new XboxUsbLed.Command(XboxUsbLed.Operation.SET, step));
            record("USB_HID_BRIGHTNESS " + step);
        }
    }

    static String recordLayout(byte[] info) throws IOException {
        if (Arrays.equals(info, new byte[]{0x60, 0})) return "legacy two-byte address";
        if (Arrays.equals(info, new byte[]{29, 0x60, 0})) return "three-byte capacity/address";
        throw new IOException("Unknown record-0 metadata; stopping before raw read: " + HEX.formatHex(info));
    }

    static Command flashRead(long address, int length) {
        if (address < APPLICATION || length < 1 || length > 48 || address + length > APPLICATION + APPLICATION_SIZE)
            throw new IllegalArgumentException("Expected a bounded application read");
        return new Command(22, 0x1601, ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int)address).put((byte)length).array());
    }

    static byte[] flashRead(Usb usb, long address, int length) throws Throwable {
        usb.send(flashRead(address, length));
        byte[] bytes = usb.reply(0x1601);
        if (bytes.length != length) throw new IOException("Short application read at " + Long.toHexString(address));
        return bytes;
    }

    static void vectors(byte[] bytes) throws IOException {
        if (bytes.length < 8) throw new IOException("Missing application vectors");
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long stack = Integer.toUnsignedLong(data.getInt()), reset = Integer.toUnsignedLong(data.getInt());
        if (stack < 0x20000000L || stack > 0x20040000L || (stack & 7) != 0 || (reset & 1) == 0
                || reset < APPLICATION || reset >= APPLICATION + APPLICATION_SIZE)
            throw new IOException("Unexpected application vector table; no firmware image will be assumed");
    }

    static Path savePrivate(String name, byte[] bytes) throws Exception {
        Path file = directory.resolve(name);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes);
        record("ARTIFACT " + name + " bytes=" + bytes.length + " SHA-256="
                + HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return file;
    }

    static void readInstalledApplication() throws Throwable {
        NativeMacDialog.proceed("Connect USB to read the installed firmware",
                "1. Connect the Xbox controller to the Mac by USB.\n\n2. Leave it connected throughout the read.\n\n"
                + "3. Click OK. The runner sends the verified volatile unlock keys, then the driver's memory-read command.\n\n"
                + "It checks the application vectors and reads the application twice. It sends no EEPROM or firmware write commands.");
        try (var usb = new Usb()) {
            NativeMacDialog.progress("Reading installed firmware", "Unlocking the USB factory interface...");
            for (int i = 0; i < KEYS.length; i++) {
                factoryAttempted = true; usb.send(unlock(i)); NativeMacDialog.pause(100);
            }
            usb.reply(0x211);
            byte[] firstVectors = flashRead(usb, APPLICATION, 16);
            vectors(firstVectors);
            if (!Arrays.equals(firstVectors, flashRead(usb, APPLICATION, 16))) throw new IOException("Application vectors differ between reads");
            savePrivate("application-vectors.bin", firstVectors);
            byte[] original = null;
            usb.trace = false;
            for (int pass = 1; pass <= 2; pass++) {
                byte[] image = new byte[APPLICATION_SIZE];
                for (int offset = 0; offset < image.length; offset += 48) {
                    int length = Math.min(48, image.length - offset);
                    byte[] chunk = flashRead(usb, APPLICATION + offset, length);
                    System.arraycopy(chunk, 0, image, offset, length);
                    if (offset % (48 * 128) == 0) {
                        NativeMacDialog.progress("Reading installed firmware", "Pass " + pass + " of 2: "
                                + (100L * offset / image.length) + "%\n\nKeep the controller connected by USB. No firmware writes are sent.");
                    }
                    if (offset % (48 * 1024) == 0) record("FLASH_READ pass=" + pass + " offset=" + offset);
                }
                vectors(image);
                if (!Arrays.equals(firstVectors, Arrays.copyOf(image, firstVectors.length))) throw new IOException("Application vector recheck failed");
                savePrivate("application-pass-" + pass + ".bin", image);
                if (original == null) original = image;
                else if (!Arrays.equals(original, image)) throw new IOException("Application reads differ; image is not verified");
            }
            usb.trace = true;
            if (!Arrays.equals(firstVectors, flashRead(usb, APPLICATION, 16))) throw new IOException("Final application vector recheck failed");
            record("VERIFIED_INSTALLED_APPLICATION base=10027000 length=" + APPLICATION_SIZE + " two identical reads");
        }
    }

    static void usb(boolean readOnly) throws Throwable {
        NativeMacDialog.proceed("Connect USB for the factory tests",
                "1. Connect the Xbox controller directly to the Mac by USB. Keep the XIAO connected.\n\n"
                + "2. Wait for a steady Xbox light.\n\n3. Click OK. "
                + (readOnly ? "This resumes only the volatile unlock and record-0 reads."
                : "The campaign checks USB brightness, volatile factory commands and record-0 reads.")
                + " No EEPROM writes are included.");
        try {
            int observation;
            if (!readOnly) {
                do {
                    ready("USB brightness baseline", "This uses the USB HID command that previously worked.");
                    for (int i = 0; i < STEPS.length; i++) {
                        NativeMacDialog.progress("USB baseline", "Request " + (i + 1) + " of 4: brightness " + STEPS[i]);
                        hidLed(STEPS[i]); NativeMacDialog.pause(3000);
                    }
                    observation = observe("USB HID baseline");
                } while (observation == 2);
                if (observation != 0) throw new IOException("USB baseline did not visibly work; stopping factory tests");
            }
            try (var usb = new Usb()) {
                NativeMacDialog.proceed("Start volatile USB factory tests",
                        "1. Leave the controller connected by USB.\n\n2. Click OK to send the three recovered unlock keys.\n\n"
                        + "These change a RAM permission level. Later commands "
                        + (readOnly ? "read configuration." : "request LED intensity and read configuration.")
                        + " The final dialog will ask for a power cycle.");
                NativeMacDialog.progress("Factory unlock", "Sending the three keys recovered from Microsoft's USB driver...");
                for (int i = 0; i < KEYS.length; i++) {
                    factoryAttempted = true; usb.send(unlock(i)); NativeMacDialog.pause(100);
                }
                usb.reply(0x211);
                record("USB_UNLOCK matching successful reply");
                if (!readOnly) {
                    do {
                        ready("USB factory LED", "This sends internal steady-LED command 0x0b01 through vendor USB requests.");
                        for (int i = 0; i < STEPS.length; i++) {
                            NativeMacDialog.progress("USB factory LED", "Request " + (i + 1) + " of 4: brightness " + STEPS[i]);
                            usb.send(led(STEPS[i])); usb.reply(0xb01); NativeMacDialog.pause(3000);
                        }
                        observation = observe("USB factory LED");
                    } while (observation == 2);
                }
                NativeMacDialog.proceed("Read the stored brightness cap",
                        "Click OK to look up logical EEPROM record 0 and read its 32-byte slot twice.\n\n"
                        + "The runner checks the address, framing and checksum, and saves matching reads privately. No configuration is written.");
                NativeMacDialog.progress("Reading configuration", "Looking up record 0 before reading its slot...");
                usb.send(new Command(3, 0x308, new byte[2]));
                byte[] info = usb.reply(0x308);
                record("RECORD_LAYOUT " + recordLayout(info));
                usb.send(new Command(3, 0x300, new byte[]{0x60, 0, 32}));
                byte[] first = usb.reply(0x300);
                int cap = recordCap(first);
                usb.send(new Command(3, 0x300, new byte[]{0x60, 0, 32}));
                byte[] second = usb.reply(0x300);
                if (!Arrays.equals(first, second)) throw new IOException("Record-0 reads differ; no verified snapshot saved");
                Path snapshot = directory.resolve("record-0-slot.bin");
                Files.createFile(snapshot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(snapshot, first);
                record("RECORD_0_BYTE_22 " + cap + " candidate brightness cap; repeated read and checksum verified; snapshot=" + snapshot);
            }
        } finally {
            if (!readOnly) try { hidLed(20); } catch (Throwable failure) { record("USB_LED_CLEANUP_FAILED " + failure); }
        }
    }

    static void powerCycle() throws Throwable {
        if (!factoryAttempted) return;
        int answer = NativeMacDialog.choose("Clear volatile factory state",
                "The campaign sent factory unlock keys.\n\n1. Unplug the controller's USB cable.\n\n"
                + "2. Hold the Xbox button for about six seconds until the controller is off.\n\n"
                + "3. Turn it on again if needed.\n\n4. Click OK after the power cycle. This clears any volatile factory permission changes.",
                "OK", "Skip");
        record(answer == 0 ? "USER_CONFIRMED power-cycle complete" : "CLEANUP_PENDING controller power-cycle skipped");
    }

    static void selfTest() throws Exception {
        if (!HEX.formatHex(led(20).frame()).equals("000b010b010014")
                || !HEX.formatHex(wrapped(led(0), 1)).equals("1f00010802000b010b010000")
                || unlock(0).usbValue() != 0x20c || new Command(3, 0x308, new byte[2]).usbValue() != 0x30e)
            throw new AssertionError("Factory framing mismatch");
        for (int command : new int[]{8, 0x301, 0x303, 0xff03, 0xff04}) {
            try { new Command(3, command, new byte[]{0}); throw new AssertionError("Accepted non-campaign command"); }
            catch (IllegalArgumentException expected) { }
        }
        for (int step : new int[]{-256, -1, 21, 256}) {
            try { led(step); throw new AssertionError("Accepted invalid campaign brightness"); }
            catch (IllegalArgumentException expected) { }
        }
        for (CommandSpec bad : List.of(new CommandSpec(14, 8, "000000000000"), new CommandSpec(3, 0x300, "610020"),
                new CommandSpec(3, 0x308, "0100"), new CommandSpec(2, 0x20f, "0000"))) {
            try { new Command(bad.selector, bad.message, HEX.parseHex(bad.hex)); throw new AssertionError("Accepted invalid command data"); }
            catch (IllegalArgumentException expected) { }
        }
        byte[] slot = new byte[32]; slot[1] = 26; slot[24] = 80; slot[28] = 80;
        if (recordCap(slot) != 80 || !Arrays.equals(responsePayload(HEX.parseHex("000803031d6000"), 0x308), new byte[]{29, 0x60, 0}))
            throw new AssertionError("Read parser mismatch");
        slot[24] ^= 1;
        try { recordCap(slot); throw new AssertionError("Accepted corrupt record"); } catch (IOException expected) { }
        for (String bad : List.of("", "000803031d60", "000003031d6000", "02080300", "00080339")) {
            try { responsePayload(HEX.parseHex(bad), 0x308); throw new AssertionError("Accepted invalid response"); }
            catch (IOException expected) { }
        }
        if (!recordLayout(HEX.parseHex("6000")).startsWith("legacy")
                || !recordLayout(HEX.parseHex("1d6000")).startsWith("three"))
            throw new AssertionError("Known record metadata layouts rejected");
        for (String bad : List.of("", "600000", "6100", "1d6100", "600001", "60001d")) {
            try { recordLayout(HEX.parseHex(bad)); throw new AssertionError("Accepted unknown record metadata"); }
            catch (IOException expected) { }
        }
        if (!HEX.formatHex(flashRead(APPLICATION, 16).data()).equals("0070021010"))
            throw new AssertionError("Flash-read byte order mismatch");
        for (long[] bad : new long[][]{{APPLICATION - 1, 1}, {APPLICATION, 0}, {APPLICATION, 49},
                {APPLICATION + APPLICATION_SIZE - 1, 2}, {0x20000000L, 1}}) {
            try { flashRead(bad[0], (int)bad[1]); throw new AssertionError("Accepted an unbounded memory read"); }
            catch (IllegalArgumentException expected) { }
        }
        vectors(HEX.parseHex("900d0320c1710210"));
        for (String bad : List.of("", "900d0310c1710210", "900d0320c0710210", "910d0320c1710210")) {
            try { vectors(HEX.parseHex(bad)); throw new AssertionError("Accepted invalid application vectors"); }
            catch (IOException expected) { }
        }
        System.out.println("Campaign self-test passed: exact frames, command whitelist, reply correlation/lengths and record checksum.");
    }

    record CommandSpec(int selector, int message, String hex) {}

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (!(args.length == 2 && args[0].equals("--run") || args.length == 1 && List.of("--usb-only", "--usb-read", "--usb-image").contains(args[0])))
            throw new IllegalArgumentException("Usage: XboxBrightnessCampaign.java --self-test | --run PORT | --usb-only | --usb-read | --usb-image");
        directory = Path.of("target", "campaigns", Instant.now().toString().replace(':', '-'));
        Files.createDirectories(directory);
        Path log = directory.resolve("campaign.log");
        Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = new PrintWriter(Files.newBufferedWriter(log))) {
            transcript = writer;
            record("CAMPAIGN " + directory.toAbsolutePath());
            try {
                if (args[0].equals("--usb-image")) {
                    readInstalledApplication();
                    record("RESULT Installed application read and verified");
                    return;
                }
                NativeMacDialog.proceed("New brightness test campaign",
                    (args[0].equals("--usb-read") ? "Resume record-0 reads over USB after the completed brightness trials.\n\n"
                    : "1. XIAO: four bounded factory-command trials, with observation dialogs.\n\n"
                    + "2. USB: known brightness baseline, volatile factory unlock, steady LED command and EEPROM reads.\n\n"
                    )
                    + "No persistent writes or firmware updates are included. Cancel stops at any prompt; Didn't see repeats an observation trial.\n\nClick OK to begin.");
                if (args[0].equals("--run") && ble(args[1])) {
                    record("RESULT BLE change reported again after reconnect; stop for analysis");
                    NativeMacDialog.choose("BLE result needs analysis", "The brightness change was reported twice, including after reconnect. The campaign stopped here to inspect that result.", "OK");
                    return;
                }
                usb(args[0].equals("--usb-read"));
                record("RESULT Campaign completed");
                NativeMacDialog.choose("Campaign complete", "The completed trials and observations are in:\n\n" + directory.toAbsolutePath(), "OK");
            } catch (CancellationException cancelled) { record("CANCELLED " + cancelled.getMessage()); }
            catch (Throwable failure) {
                record("STOPPED " + failure);
                NativeMacDialog.choose("Campaign stopped", failure.getMessage() + "\n\nCompleted results are saved in:\n" + directory.toAbsolutePath(), "OK");
                throw failure;
            } finally { NativeMacDialog.hideProgress(); powerCycle(); }
        }
    }
}
