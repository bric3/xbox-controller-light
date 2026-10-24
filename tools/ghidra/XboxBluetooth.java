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
            verifyIngress(writer);
            verifyClamp(writer);
            verifyVendorStates(writer);
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

    private void verifyIngress(PrintWriter writer) throws Exception {
        if (getInt(toAddr(0x1002aa50L)) != 0x20005831 || Short.toUnsignedInt(getShort(toAddr(0x20005858L))) != 0xe7fe)
            throw new AssertionError("Expected non-returning firmware assertion handler");
        for (int source : new int[]{4, 17, 24}) {
            var emulator = emulator();
            try {
                emulator.writeRegister("r0", source);
                emulator.writeRegister("r2", PAYLOAD);
                emulator.writeRegister("r3", 7);
                long stop = trace(emulator, 0x1004acd2L, Set.of(0x1004ad0aL, 0x1004acfcL), writer);
                if (source == 24) {
                    if (stop != 0x1004acfcL) throw new AssertionError("BLE ingress did not assert");
                    writer.println("GIP ingress from BLE task 24: reaches assertion before GIP parser.");
                } else {
                    if (stop != 0x1004ad0aL || emulator.readRegister("r0").intValue() != (source == 4 ? 1 : 2)
                            || emulator.readRegister("r1").longValue() != PAYLOAD
                            || emulator.readRegister("r2").intValue() != 7)
                        throw new AssertionError("Unexpected GIP ingress route");
                    writer.printf("GIP ingress from task %d: forwards payload to GIP parser with transport %d.%n",
                            source, emulator.readRegister("r0").intValue());
                }
            } finally { emulator.dispose(); }
        }
    }

    private void verifyClamp(PrintWriter writer) throws Exception {
        long state = Integer.toUnsignedLong(getInt(toAddr(0x10075834L)));
        for (int cap : new int[]{0, 5, 20, 100}) {
            for (int requested : new int[]{0, 5, 20, 47}) {
                var emulator = emulator();
                try {
                    emulator.writeMemoryValue(toAddr(state + 0x48), 1, cap);
                    emulator.writeRegister("r0", 0);
                    emulator.writeRegister("r1", requested);
                    long stop = trace(emulator, 0x100757fcL, Set.of(0x10075814L, 0x1007581aL), writer);
                    int expected = Math.min(cap, requested);
                    if (stop != (expected == 0 ? 0x1007581aL : 0x10075814L)
                            || emulator.readRegister("r1").intValue() != expected)
                        throw new AssertionError("Unexpected brightness clamp");
                    writer.printf("LED policy cap %d, requested %d: PWM brightness %d (%s path).%n",
                            cap, requested, expected, expected == 0 ? "off" : "on");
                } finally { emulator.dispose(); }
            }
        }
    }

    private void verifyVendorStates(PrintWriter writer) throws Exception {
        long host = Integer.toUnsignedLong(getInt(toAddr(0x1004cc54L)));
        long app = Integer.toUnsignedLong(getInt(toAddr(0x1004cc50L)));
        long[] entries = {0x1004bd54L, 0x00106864L, 0x1004bdd0L, 0x00106cf8L, 0x00106cf8L, 0x00107498L,
                0x1004c5caL, 0x1004c324L, 0x1004c390L, 0x1004c406L, 0x1004c4c8L, 0x1004c514L};
        String[] names = {"idle", "detect", "enrolling", "connected radio", "connected BLE", "connected USB",
                "switching", "enrolment 0", "enrolment 1", "enrolment 2", "enrolment 3", "enrolment 4"};
        for (int state = 0; state < entries.length; state++) {
            for (int first : new int[]{0, 10, 255}) {
                var emulator = emulator();
                try {
                    emulator.writeMemory(toAddr(host), new byte[0x500]);
                    emulator.writeMemory(toAddr(app), new byte[0x200]);
                    emulator.writeMemoryValue(toAddr(host + 4), 1, 29);
                    emulator.writeMemoryValue(toAddr(host + 0x11), 1, state == 3 ? 2 : state == 5 ? 1 : 3);
                    emulator.writeMemoryValue(toAddr(app + 0x118), 1, 0xa5);
                    emulator.writeMemory(toAddr(PAYLOAD), new byte[]{(byte)first, 0x20, 1, 3, 0, 1, 10});
                    if (state >= 3 && state <= 5) {
                        emulator.writeRegister("r0", host);
                        emulator.writeRegister("r1", 24);
                        emulator.writeRegister("r2", 0x28);
                        emulator.writeRegister("r3", PAYLOAD);
                        emulator.writeStackValue(0, 4, 7);
                        emulator.writeStackValue(4, 4, 0);
                    } else {
                        emulator.writeRegister("r0", 24);
                        emulator.writeRegister("r1", 0x28);
                        emulator.writeRegister("r2", PAYLOAD);
                        emulator.writeRegister("r3", 7);
                        emulator.writeStackValue(0, 4, 0);
                    }
                    String result = traceVendorState(emulator, entries[state]);
                    if (Byte.toUnsignedInt(emulator.readMemoryByte(toAddr(app + 0x118))) != 0xa5)
                        throw new AssertionError("Unexpected vendor brightness change");
                    writer.printf("Vendor command 0x28, state %s, payload %02x: %s; no task dispatch, brightness unchanged.%n",
                            names[state], first, result);
                    writer.flush();
                } finally { emulator.dispose(); }
            }
        }
    }

    private String traceVendorState(EmulatorHelper emulator, long start) throws Exception {
        emulator.writeRegister("pc", start);
        for (int step = 0; step < 2000; step++) {
            long pc = emulator.getExecutionAddress().getOffset();
            if (pc == 0x100a4f00L) return "returned";
            // Enrolment dispatch is checked separately through each of its five callbacks.
            if (start == 0x1004bdd0L && pc == 0x1004bf42L) return "enrolment subdispatch boundary";
            var function = getFunctionAt(toAddr(pc));
            if (pc == 0x1004a9aeL || (function != null && function.getName().contains("1004a9ae")))
                return "unknown-command logger";
            if (Set.of(0x001043b4L, 0x001043a4L, 0x001042ceL, 0x001042beL, 0x1004ac04L,
                    0x10074ffcL, 0x100756f6L).contains(pc))
                throw new AssertionError("Vendor state reached a task dispatch at " + Long.toHexString(pc));
            var instruction = getInstructionAt(toAddr(pc));
            var containing = getFunctionContaining(toAddr(pc));
            if (instruction != null && (instruction.getFlowType().isTerminal()
                    || (instruction.getMnemonicString().startsWith("pop") && instruction.toString().contains("pc")))
                    && containing != null && containing.getEntryPoint().getOffset() == start)
                return "return boundary";
            if (instruction != null && instruction.getFlowType().isCall() && instruction.getFlows().length == 1) {
                var target = getFunctionAt(instruction.getFlows()[0]);
                if (target != null && target.getName().contains("1003eef4")) {
                    // The ingress diagnostic logger has no role in command routing.
                    emulator.writeRegister("pc", instruction.getMaxAddress().next().getOffset());
                    continue;
                }
                if (target != null && target.getName().contains("1004a9ae")) return "unknown-command logger";
            }
            if (!emulator.step(monitor)) throw new AssertionError("Vendor state stopped at "
                    + Long.toHexString(pc) + ": " + emulator.getLastError());
        }
        throw new AssertionError("Vendor state instruction limit exceeded");
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
