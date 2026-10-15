// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

// Offline checks for the pinned MERL 5.23.6.0 image; no controller access.
public class XboxBluetooth extends GhidraScript {
    private static final long PAYLOAD = 0x20038000L;
    private static final long STACK = 0x2003f000L;

    @Override
    protected void run() throws Exception {
        Path output = Path.of(getScriptArgs()[0]).resolve("bluetooth-path-verification.txt");
        try (var writer = new PrintWriter(Files.newBufferedWriter(output))) {
            long host = Integer.toUnsignedLong(getInt(toAddr(0x1004cc54L)));
            long app = Integer.toUnsignedLong(getInt(toAddr(0x1004cc50L)));
            // Test the connected wireless state and its unknown-command destination directly.
            for (int firstByte : new int[]{0, 1, 10, 255}) {
                var emulator = emulator();
                try {
                    emulator.writeMemory(toAddr(host), new byte[0x500]);
                    emulator.writeMemory(toAddr(app), new byte[0x200]);
                    emulator.writeMemoryValue(toAddr(host + 4), 1, 29);
                    emulator.writeMemoryValue(toAddr(host + 0x11), 1, 3);
                    emulator.writeMemoryValue(toAddr(app + 0x118), 1, 0xa5);
                    emulator.writeMemory(toAddr(PAYLOAD), new byte[]{(byte) firstByte, 0x20, 1, 3, 0, 1, 10});
                    emulator.writeRegister("r0", host);
                    emulator.writeRegister("r1", 24);
                    emulator.writeRegister("r2", 0x28);
                    emulator.writeRegister("r3", PAYLOAD);
                    emulator.writeStackValue(0, 4, 7);
                    emulator.writeStackValue(4, 4, 0);
                    // Stop before BL: Ghidra's legacy ARM emulator cannot execute its ISA-mode update.
                    long stop = trace(emulator, 0x00106cf8L, Set.of(0x0010741eL), writer);
                    if (stop != 0x0010741eL || getInt(toAddr(0x00102164L)) != 0x1004a9af
                            || emulator.readRegister("r2").intValue() != 0x28
                            || Byte.toUnsignedInt(emulator.readMemoryByte(toAddr(app + 0x118))) != 0xa5)
                        throw new AssertionError("Vendor write did not reach the unknown-command path");
                    writer.printf("Vendor payload first byte %02x: connected BLE state ignores command 0x28 as unknown; brightness unchanged.%n", firstByte);
                    writer.flush();
                } finally {
                    emulator.dispose();
                }
            }
            for (int length : new int[]{0, 3, 7, 8, 13}) {
                var emulator = emulator();
                try {
                    long bluetooth = Integer.toUnsignedLong(getInt(toAddr(0x10040b8cL)));
                    emulator.writeMemoryValue(toAddr(bluetooth + 4), 1, 24);
                    emulator.writeRegister("r0", 1);
                    emulator.writeRegister("r1", PAYLOAD);
                    emulator.writeMemory(toAddr(PAYLOAD), new byte[0x100]);
                    emulator.writeMemoryValue(toAddr(PAYLOAD + 0x24), 4, length);
                    emulator.writeMemoryValue(toAddr(PAYLOAD + 0x28), 1, 1);
                    emulator.writeMemory(toAddr(PAYLOAD + 0x2a), new byte[]{10, 0x20, 1, 3, 0, 1, 10, 0});
                    long stop = trace(emulator, 0x100408deL, Set.of(0x100408faL, 0x10040928L), writer);
                    if (length < 8) {
                        if (stop != 0x100408faL) throw new AssertionError("Short HID write accepted");
                        // Omit the diagnostic logger; resume at the firmware's length-error response.
                        trace(emulator, 0x100408feL, Set.of(0x10040954L), writer);
                        if (emulator.readRegister("r1").longValue() != 0xffffffffL)
                            throw new AssertionError("Short HID write did not return an error");
                        writer.printf("HID output %d bytes: rejected by controller callback.%n", length);
                    } else {
                        if (stop != 0x10040928L || emulator.readRegister("r0").intValue() != 24
                                || emulator.readRegister("r1").intValue() != 10
                                || emulator.readRegister("r2").intValue() != 0x1200
                                || emulator.readRegister("r3").longValue() != PAYLOAD + 0x2a
                                || emulator.readStackValue(0, 4, false).intValue() != 8)
                            throw new AssertionError("Unexpected HID output routing");
                        writer.printf("HID output %d bytes: first eight bytes queued as rumble to task 10, command 0x1200.%n", length);
                    }
                    writer.flush();
                } finally {
                    emulator.dispose();
                }
            }
            var control = emulator();
            try {
                control.writeRegister("r0", 1);
                trace(control, 0x1004072cL, Set.of(0x1003eef4L), writer);
                if (control.readRegister("r2").longValue() != 0x10040e24L)
                    throw new AssertionError("Unexpected HID Control Point handler");
                writer.println("HID Control Point: tail-call to LEHIDCtrl diagnostic logger only.");
            } finally {
                control.dispose();
            }
        }
        println("Bluetooth path checks passed: " + output);
    }

    private EmulatorHelper emulator() {
        var emulator = new EmulatorHelper(currentProgram);
        for (int i = 0; i < 13; i++) emulator.writeRegister("r" + i, 0);
        for (String flag : new String[]{"CY", "ZR", "NG", "OV"}) emulator.writeRegister(flag, 0);
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("lr", 0x100a4f01L);
        emulator.setContextRegister(currentProgram.getRegister("TMode"), BigInteger.ONE);
        return emulator;
    }

    private long trace(EmulatorHelper emulator, long start, Set<Long> stops, PrintWriter writer) throws Exception {
        emulator.writeRegister("pc", start);
        for (int step = 0; step < 2000; step++) {
            long pc = emulator.getExecutionAddress().getOffset();
            if (stops.contains(pc)) return pc;
            if (Set.of(0x001043b4L, 0x001043a4L, 0x001042ceL, 0x001042beL, 0x1004ac04L,
                    0x10074ffcL, 0x100756f6L).contains(pc))
                throw new AssertionError("Unexpected task dispatch or LED path at " + Long.toHexString(pc));
            if (!emulator.step(monitor)) {
                writer.printf("Stopped at %08x: %s%n", pc, emulator.getLastError());
                throw new AssertionError("Emulation failed at " + Long.toHexString(pc) + ": " + emulator.getLastError());
            }
        }
        throw new AssertionError("Emulation instruction limit exceeded");
    }
}
