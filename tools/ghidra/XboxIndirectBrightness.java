// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.lang.Register;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Offline checks of indirect controls; no controller, pairing or connection-pattern tests. */
public class XboxIndirectBrightness extends GhidraScript {
    private static final long DATA = 0x20038000L, OBJECT = 0x20039000L, VTABLE = 0x20039200L;
    private static final long STACK = 0x2003f000L, RETURN = 0x100a4f00L;
    private static final long APP = 0x200040c8L, CAP = 0x2002bee0L, COLOUR = 0x2002cd76L;

    @FunctionalInterface private interface Hook { boolean handle(long pc, EmulatorHelper emulator) throws Exception; }

    @Override protected void run() throws Exception {
        Path output = Path.of(getScriptArgs()[0]).resolve("indirect-brightness-verification.txt");
        try (var writer = new PrintWriter(Files.newBufferedWriter(output))) {
            checkMemcpy(writer);
            checkStoredCap(writer);
            checkConfigurationCopy(writer);
            checkTestCommandIngress(writer);
            checkL2capRegistry(writer);
            checkRecordFraming(writer);
            checkActiveCap(writer);
            checkColour(writer);
            checkRumble(writer);
            checkBattery(writer);
            checkPwmChannels(writer);
            findConfigurationDispatcher(writer);
            writer.println("PASS: all indirect brightness checks completed; external services mocked, no hardware writes.");
        }
        println("Indirect brightness checks passed: " + output);
    }

    private EmulatorHelper emulator() {
        var e = new EmulatorHelper(currentProgram);
        registers(e);
        return e;
    }

    private void registers(EmulatorHelper e) {
        for (int i = 0; i < 13; i++) e.writeRegister("r" + i, 0);
        for (String flag : new String[]{"CY", "ZR", "NG", "OV"}) e.writeRegister(flag, 0);
        e.writeRegister("sp", STACK);
        e.writeRegister("lr", RETURN | 1);
        e.setContextRegister(currentProgram.getRegister("TMode"), BigInteger.ONE);
    }

    private long reg(EmulatorHelper e, String name) { return e.readRegister(name).longValue() & 0xffffffffL; }
    private int byteAt(EmulatorHelper e, long address) { return Byte.toUnsignedInt(e.readMemoryByte(toAddr(address))); }
    private long word(EmulatorHelper e, long address) {
        long value = 0;
        for (int i = 0; i < 4; i++) value |= (long)byteAt(e, address + i) << (i * 8);
        return value;
    }
    private void value(EmulatorHelper e, long address, int size, long value) { e.writeMemoryValue(toAddr(address), size, value); }
    private void returned(EmulatorHelper e, long result) {
        e.writeRegister("r0", result);
        e.writeRegister("pc", reg(e, "lr") & ~1L);
    }

    private void execute(EmulatorHelper e, long entry, Hook hook) throws Exception {
        e.writeRegister("pc", entry);
        for (int step = 0; step < 30000; step++) {
            long pc = e.getExecutionAddress().getOffset();
            if (pc == RETURN) return;
            if (hook.handle(pc, e)) continue;
            if (Set.of(0x1002aa4cL, 0x00100038L, 0x20005830L).contains(pc))
                throw new AssertionError("Firmware assertion at " + Long.toHexString(pc));
            var instruction = getInstructionAt(toAddr(pc));
            if (instruction == null) {
                disassemble(toAddr(pc));
                instruction = getInstructionAt(toAddr(pc));
            }
            if (instruction == null) throw new AssertionError("No instruction at " + Long.toHexString(pc));
            String mnemonic = instruction.getMnemonicString();
            // Cortex-M uses Thumb calls and returns. Handle the ISA update that the legacy emulator rejects.
            if (mnemonic.startsWith("ldr") && instruction.getDefaultOperandRepresentation(0).equalsIgnoreCase("pc")) {
                var references = Arrays.stream(instruction.getReferencesFrom())
                        .filter(r -> r.getReferenceType().isData()).toList();
                if (references.size() != 1)
                    throw new AssertionError("Cannot decode literal PC load");
                long target = word(e, references.getFirst().getToAddress().getOffset());
                if ((target & 1) == 0) throw new AssertionError("Non-Thumb literal branch");
                e.writeRegister("pc", target & ~1L);
                continue;
            }
            if (mnemonic.equals("bl") || mnemonic.equals("blx") || mnemonic.equals("bx")) {
                if (instruction.getFlowType().isConditional()) throw new AssertionError("Conditional call unsupported");
                long target;
                if (instruction.getFlows().length == 1) target = instruction.getFlows()[0].getOffset();
                else {
                    Register register = (Register)instruction.getOpObjects(0)[0];
                    target = reg(e, register.getName());
                    if ((target & 1) == 0) throw new AssertionError("Non-Thumb register branch");
                }
                if (!mnemonic.equals("bx")) e.writeRegister("lr", instruction.getMaxAddress().next().getOffset() | 1L);
                e.writeRegister("pc", target & ~1L);
                continue;
            }
            if (mnemonic.startsWith("pop") && instruction.toString().contains("pc")) {
                var list = new ArrayList<Register>();
                for (Object operand : instruction.getOpObjects(0)) if (operand instanceof Register r) list.add(r);
                if (list.isEmpty()) throw new AssertionError("Cannot decode POP register list");
                long sp = reg(e, "sp");
                for (Register r : list) {
                    long loaded = word(e, sp); sp += 4;
                    if (r.getName().equalsIgnoreCase("pc")) {
                        if ((loaded & 1) == 0) throw new AssertionError("Non-Thumb POP return");
                        loaded &= ~1L;
                    }
                    e.writeRegister(r.getName(), loaded);
                }
                e.writeRegister("sp", sp);
                continue;
            }
            if (!e.step(monitor)) throw new AssertionError("Stopped at " + Long.toHexString(pc) + ": " + e.getLastError());
        }
        throw new AssertionError("Instruction limit exceeded");
    }

    private boolean diagnostics(long pc, EmulatorHelper e) {
        // shortcut: external services are mocked; use hardware traces to establish ingress and persistence.
        if (Set.of(0x1003eef4L, 0x1003211cL, 0x10032124L, 0x0010028cL, 0x00100294L).contains(pc)) {
            returned(e, 0); return true;
        }
        return false;
    }

    private void checkMemcpy(PrintWriter writer) throws Exception {
        for (int size : new int[]{1, 7, 26}) {
            var e = emulator();
            try {
                byte[] input = new byte[size];
                for (int i = 0; i < size; i++) input[i] = (byte)(i + 1);
                e.writeMemory(toAddr(DATA + 1), input);
                e.writeRegister("r0", DATA + 0x101); e.writeRegister("r1", DATA + 1); e.writeRegister("r2", size);
                execute(e, 0x10030e74L, (pc, em) -> false);
                if (!Arrays.equals(input, e.readMemory(toAddr(DATA + 0x101), size))) throw new AssertionError("Memcpy check failed");
            } finally { e.dispose(); }
        }
        writer.println("Emulator call/return check: firmware memcpy preserves 1, 7 and 26 unaligned bytes.");
    }

    private void object(EmulatorHelper e, int state) {
        e.writeMemory(toAddr(OBJECT), new byte[0x100]);
        e.writeMemory(toAddr(VTABLE - 0x40), new byte[0x80]);
        value(e, OBJECT, 4, VTABLE); value(e, OBJECT + 5, 1, state);
    }

    private int clamp(EmulatorHelper e, int requested) throws Exception {
        registers(e); e.writeRegister("r0", 0); e.writeRegister("r1", requested);
        int[] result = {-1};
        execute(e, 0x100757fcL, (pc, em) -> {
            if (pc == 0x1007586cL || pc == 0x100758caL) {
                result[0] = pc == 0x100758caL ? 0 : (int)reg(em, "r1");
                em.writeRegister("pc", RETURN); return true;
            }
            return false;
        });
        if (result[0] < 0) throw new AssertionError("No PWM handoff");
        return result[0];
    }

    private void checkStoredCap(PrintWriter writer) throws Exception {
        if (Byte.toUnsignedInt(getByte(toAddr(0x1004adb4L + 22))) != 80)
            throw new AssertionError("Unexpected compiled configuration cap");
        for (int cap : new int[]{0, 5, 20, 80}) {
            var e = emulator();
            try {
                e.writeMemory(toAddr(APP), new byte[0x200]); value(e, APP + 4, 1, 3);
                e.writeRegister("r0", APP);
                int[] reads = {0};
                execute(e, 0x1004a380L, (pc, em) -> {
                    if (diagnostics(pc, em)) return true;
                    if (pc == 0x10040fc0L) {
                        long descriptor = reg(em, "r3");
                        int key = byteAt(em, descriptor), length = byteAt(em, descriptor + 1);
                        long destination = word(em, descriptor + 2);
                        if (reads[0]++ == 0) {
                            if (key != 0 || length != 26 || destination != APP + 0x11) throw new AssertionError("Unexpected EEPROM configuration read");
                            byte[] record = getBytes(toAddr(0x1004adb4L), 26); record[22] = (byte)cap;
                            em.writeMemory(toAddr(destination), record); returned(em, 0);
                        } else {
                            if (key != 0x50) throw new AssertionError("Unexpected second EEPROM record");
                            returned(em, 1);
                        }
                        return true;
                    }
                    return false;
                });
                if (byteAt(e, APP + 0x27) != cap || reads[0] != 2) throw new AssertionError("Stored cap not loaded");
                registers(e); e.writeRegister("r0", APP);
                boolean[] writeRequested = {false};
                execute(e, 0x1004a454L, (pc, em) -> {
                    if (pc != 0x10040fc0L) return false;
                    long d = reg(em, "r3");
                    if (reg(em, "r1") != 13 || reg(em, "r2") != 0x303 || byteAt(em, d) != 0
                            || byteAt(em, d + 1) != 26 || word(em, d + 2) != APP + 0x11)
                        throw new AssertionError("Unexpected EEPROM cache write request");
                    writeRequested[0] = true; em.writeRegister("pc", RETURN); return true;
                });
                if (!writeRequested[0]) throw new AssertionError("EEPROM writer not reached");
                registers(e);
                int[] initialisation = {-1};
                execute(e, 0x10052534L, (pc, em) -> {
                    if (pc == 0x10040fc0L) { returned(em, 1); return true; }
                    if (pc == 0x10043d14L) { returned(em, 0); return true; }
                    if (pc != 0x1004a1ecL) return false;
                    if (reg(em, "r1") == 26) {
                        if (reg(em, "r2") != 0xff03 || word(em, reg(em, "sp")) != 1)
                            throw new AssertionError("Unexpected LED initialisation message");
                        initialisation[0] = byteAt(em, reg(em, "r3"));
                    }
                    returned(em, 0); return true;
                });
                if (initialisation[0] != cap || byteAt(e, APP + 0x118) != 100)
                    throw new AssertionError("Application did not forward stored cap at startup");
                object(e, 0); e.writeMemory(toAddr(DATA), new byte[]{(byte)initialisation[0]}); value(e, CAP, 1, 100);
                registers(e); e.writeRegister("r0", OBJECT); e.writeRegister("r1", DATA); e.writeRegister("r2", 1);
                execute(e, 0x10075404L, (pc, em) -> {
                    if (pc == 0x100757fcL) { returned(em, 0); return true; }
                    return false;
                });
                if (byteAt(e, CAP) != cap || clamp(e, 20) != Math.min(cap, 20) || clamp(e, 100) != cap)
                    throw new AssertionError("Stored cap did not constrain brightness");
                writer.printf("EEPROM record 0 byte 22=%d -> app+0x27 -> initialisation cap -> requests 20/100 produce %d/%d. Internal whole-record writer confirmed.%n", cap, Math.min(cap, 20), cap);
            } finally { e.dispose(); }
        }
    }

    private void checkActiveCap(PrintWriter writer) throws Exception {
        var e = emulator();
        try {
            object(e, 3); value(e, CAP, 1, 80); e.writeMemory(toAddr(DATA), new byte[]{0});
            e.writeRegister("r0", OBJECT); e.writeRegister("r1", DATA); e.writeRegister("r2", 1);
            execute(e, 0x10075404L, (pc, em) -> false);
            if (byteAt(e, CAP) != 80) throw new AssertionError("Active cap unexpectedly changed");
            writer.println("Active hardware task: repeated initialisation with cap 0 leaves cap 80 unchanged.");
        } finally { e.dispose(); }
    }

    private void checkConfigurationCopy(PrintWriter writer) throws Exception {
        for (int length : new int[]{6, 17}) {
            var e = emulator();
            try {
                byte[] original = getBytes(toAddr(0x1004adb4L), 26);
                byte[] payload = Arrays.copyOfRange(original, 6, 6 + length);
                if (length == 17) payload[16] = 5;
                e.writeMemory(toAddr(APP), new byte[0x200]); value(e, APP + 4, 1, 3);
                e.writeMemory(toAddr(APP + 0x11), original); e.writeMemory(toAddr(DATA), payload);
                e.writeRegister("r0", APP); e.writeRegister("r1", 4); e.writeRegister("r2", 8); e.writeRegister("r3", DATA);
                e.writeStackValue(0, 4, length); e.writeStackValue(4, 4, 0);
                boolean[] requested = {false};
                execute(e, 0x00102180L, (pc, em) -> {
                    if (diagnostics(pc, em)) return true;
                    if (pc != 0x001042deL) return false;
                    long d = reg(em, "r3");
                    if (reg(em, "r1") != 13 || reg(em, "r2") != 0x303 || byteAt(em, d) != 0
                            || byteAt(em, d + 1) != 26) throw new AssertionError("Unexpected staged record write");
                    byte[] expected = original.clone();
                    if (length == 17) expected[22] = 5;
                    if (!Arrays.equals(expected, em.readMemory(toAddr(word(em, d + 2)), 26)))
                        throw new AssertionError("Configuration copy changed unexpected record bytes");
                    requested[0] = true; em.writeRegister("pc", RETURN); return true;
                });
                if (!requested[0]) throw new AssertionError("Configuration copy did not reach EEPROM boundary");
            } finally { e.dispose(); }
        }
        writer.println("Internal command 8: six-byte copy preserves cap; oversized 17-byte copy can change only record byte 22 to 5. EEPROM service stopped before write; hardware ingress not tested.");
    }

    private void checkRecordFraming(PrintWriter writer) throws Exception {
        var e = emulator();
        try {
            e.writeRegister("r1", 0); e.writeRegister("r2", DATA);
            execute(e, 0x1006c490L, (pc, em) -> false);
            if (reg(e, "r0") != 1 || byteAt(e, DATA) != 29)
                throw new AssertionError("Unexpected EEPROM record-0 capacity");
            int address = byteAt(e, DATA + 1) | byteAt(e, DATA + 2) << 8;
            if (address != 0x60) throw new AssertionError("Unexpected EEPROM record-0 address");
            byte[] payload = getBytes(toAddr(0x1004adb4L), 26); payload[22] = 5;
            e.writeMemory(toAddr(DATA + 0x100), payload);
            registers(e); e.writeRegister("r1", 0); e.writeRegister("r2", 26); e.writeRegister("r3", DATA + 0x100);
            execute(e, 0x1006c77aL, (pc, em) -> false);
            long buffer = Integer.toUnsignedLong(getInt(toAddr(0x1006cf98L)));
            int checksum = 0;
            for (byte value : payload) checksum = (checksum + Byte.toUnsignedInt(value)) & 0xff;
            if (byteAt(e, buffer) != 0 || byteAt(e, buffer + 1) != 26 || byteAt(e, buffer + 28) != checksum
                    || !Arrays.equals(payload, e.readMemory(toAddr(buffer + 2), 26)))
                throw new AssertionError("EEPROM record framing mismatch");
            writer.printf("EEPROM record 0: slot capacity 29, logical address 0x%04x; the application's 26-byte payload produces a 29-byte frame containing ID, length, payload and additive checksum. No storage I/O executed.%n", address);
        } finally { e.dispose(); }
    }

    private void checkTestCommandIngress(PrintWriter writer) throws Exception {
        long permission = 0x20006a5aL;
        if (Byte.toUnsignedInt(getByte(toAddr(permission))) != 2
                || getByte(toAddr(0x1007ac08L + 3)) != 13
                || getByte(toAddr(0x1007ac08L + 10)) != 26
                || getByte(toAddr(0x1007ac08L + 11)) != 8
                || getByte(toAddr(0x1007ac08L + 14)) != 4)
            throw new AssertionError("Unexpected initial permission or test-command task map");

        int[] commands = {8, 0xb01, 0x300, 0x308, 0xff00, 0x20f, 0x210, 0x211, 0xe0308};
        for (int level = 0; level <= 3; level++) {
            for (int command : commands) {
                var e = emulator();
                try {
                    value(e, permission, 1, level); e.writeRegister("r0", command);
                    execute(e, 0x10054890L, (pc, em) -> false);
                    boolean expected = level == 0 || level == 1 && command != 0xe0308
                            || level == 2 && Set.of(0xff00, 0x20f, 0x210, 0x211).contains(command);
                    if (reg(e, "r0") != (expected ? 1 : 0))
                        throw new AssertionError("Unexpected factory permission for " + Integer.toHexString(command) + " at " + level);
                } finally { e.dispose(); }
            }
        }
        writer.println("Factory permission gate: 36 cases across levels 0-3; startup level 2 allows FF00/020F/0210/0211 only. Level 1 permits command 8, LED B01 and NV reads 300/308; flagged E0308 fails the full-word gate.");

        int[][] routes = {{14, 4, 8, 6}, {14, 4, 8, 17}, {11, 8, 0xb01, 1}, {3, 13, 0x308, 2}};
        for (int level : new int[]{1, 2}) {
            for (int[] route : routes) {
                for (boolean truncated : new boolean[]{false, true}) {
                    var e = emulator();
                    try {
                        object(e, 3); value(e, OBJECT + 4, 1, 3); value(e, permission, 1, level);
                        long testState = Integer.toUnsignedLong(getInt(toAddr(0x100703f0L)));
                        value(e, testState + 0x13, 1, 0);
                        int length = route[3];
                        byte[] frame = ByteBuffer.allocate(6 + length).order(ByteOrder.LITTLE_ENDIAN)
                                .put((byte)0).put((byte)route[0]).putShort((short)route[2])
                                .putShort((short)length).array();
                        e.writeMemory(toAddr(DATA), frame);
                        e.writeRegister("r0", OBJECT); e.writeRegister("r1", 17);
                        e.writeRegister("r2", 0x20e); e.writeRegister("r3", DATA);
                        e.writeStackValue(0, 4, truncated ? frame.length - 1 : frame.length); e.writeStackValue(4, 4, 0);
                        boolean[] queued = {false};
                        execute(e, 0x1006f972L, (pc, em) -> {
                            if (diagnostics(pc, em)) return true;
                            if (pc == 0x1003edfcL) { em.writeRegister("pc", RETURN); return true; }
                            if (pc != 0x001043b4L) return false;
                            if (reg(em, "r0") != 3 || reg(em, "r1") != route[1] || reg(em, "r2") != route[2]
                                    || reg(em, "r3") != DATA + 6 || word(em, reg(em, "sp")) != length)
                                throw new AssertionError("Unexpected test-command queue request: r0=" + reg(em, "r0")
                                        + ", r1=" + reg(em, "r1") + ", r2=" + reg(em, "r2")
                                        + ", r3=" + reg(em, "r3") + ", length=" + word(em, reg(em, "sp")));
                            queued[0] = true; em.writeRegister("pc", RETURN); return true;
                        });
                        if (queued[0] != (level == 1 && !truncated))
                            throw new AssertionError("Test-command permission/length check mismatch");
                    } finally { e.dispose(); }
                }
            }
        }
        writer.println("GIP test dispatcher: 16 cases verify selectors 14->application task 4 (command 8, lengths 6/17), 11->LED task 8, 3->NV task 13; level-2 and truncated requests enqueue nothing. Stops before scheduler execution; no storage or hardware calls.");

        for (boolean valid : new boolean[]{false, true}) {
            var e = emulator();
            try {
                object(e, 3); value(e, permission, 1, 2);
                long testState = Integer.toUnsignedLong(getInt(toAddr(0x100703f0L)));
                value(e, testState + 0x13, 1, 0);
                int[] keys = {0x51db, 0xff6e, valid ? 0xabaa : 0};
                for (int i = 0; i < keys.length; i++) {
                    registers(e); value(e, DATA, 2, keys[i]);
                    e.writeRegister("r0", OBJECT); e.writeRegister("r1", 4);
                    e.writeRegister("r2", 0x20f + i); e.writeRegister("r3", DATA);
                    e.writeStackValue(0, 4, 2); e.writeStackValue(4, 4, 0);
                    execute(e, 0x1006f972L, (pc, em) -> {
                        if (diagnostics(pc, em)) return true;
                        if (pc == 0x1003edfcL) { em.writeRegister("pc", RETURN); return true; }
                        return false;
                    });
                    int expected = i < 2 ? i + 1 : valid ? 3 : 0;
                    if (byteAt(e, testState + 0x13) != expected || byteAt(e, permission) != (valid && i == 2 ? 1 : 2))
                        throw new AssertionError("Factory unlock state mismatch");
                }
            } finally { e.dispose(); }
        }
        writer.println("Factory unlock: valid 020F/0210/0211 sequence lowers RAM permission from 2 to 1; wrong final key resets sequence and leaves permission 2. Six offline steps, no transport or storage calls.");
    }

    private void checkL2capRegistry(PrintWriter writer) throws Exception {
        int[] psms = {1, 3, 0x17, 0x1b, 0x19, 0x3a27, 0x11, 0x13, 0x1f};
        if (Byte.toUnsignedInt(getByte(toAddr(0x100421f6L))) != psms.length)
            throw new AssertionError("Unexpected L2CAP registry length");
        for (int i = 0; i < psms.length; i++) {
            long entry = 0x1007b388L + i * 24L;
            if (Short.toUnsignedInt(getShort(toAddr(entry + 2))) != psms[i])
                throw new AssertionError("Unexpected L2CAP registry PSM");
            for (int offset : new int[]{4, 8}) {
                long callback = Integer.toUnsignedLong(getInt(toAddr(entry + offset))) & ~1L;
                if (psms[i] == 0x1f) continue;
                var e = emulator();
                try {
                    execute(e, callback, (pc, em) -> false);
                    long expected = psms[i] == 0x1b && offset == 4 || psms[i] == 0x17 && offset == 4 ? 0 : 0x03fffff1L;
                    if (reg(e, "r0") != expected) throw new AssertionError("L2CAP callback is not the expected stub");
                } finally { e.dispose(); }
            }
            writer.printf("L2CAP registry PSM %04x: %s.%n", psms[i], psms[i] == 0x1f
                    ? "ATT callbacks 100791bc/100790da; no separate GIP service identified"
                    : "both callbacks are constant-return stubs, with no command dispatch");
        }
    }

    private void checkColour(PrintWriter writer) throws Exception {
        if (getByte(toAddr(0x1007d8a9L)) != 1) throw new AssertionError("Expected one physical LED channel");
        for (int coefficient : new int[]{0, 1, 20, 100, 255}) {
            var e = emulator();
            try {
                e.writeMemory(toAddr(DATA), new byte[]{(byte)coefficient});
                e.writeRegister("r0", 0); e.writeRegister("r1", DATA); e.writeRegister("r2", 1);
                execute(e, 0x10075908L, (pc, em) -> false);
                if (byteAt(e, COLOUR) != coefficient) throw new AssertionError("Colour coefficient not stored");
                registers(e); e.writeRegister("r0", 0); e.writeRegister("r1", 20);
                int[] duty = {-1};
                execute(e, 0x1007586cL, (pc, em) -> {
                    if (pc != 0x10075950L) return false;
                    duty[0] = (int)reg(em, "r1"); em.writeRegister("pc", RETURN); return true;
                });
                if (duty[0] != 20) throw new AssertionError("Single-channel colour scaling changed brightness");
            } finally { e.dispose(); }
        }
        writer.println("Colour coefficient 0, 1, 20, 100, 255: single physical LED always receives requested duty 20; colour scaling bypassed.");
    }

    private void checkRumble(PrintWriter writer) throws Exception {
        long motor = Integer.toUnsignedLong(getInt(toAddr(0x100785bcL)));
        long channels = Integer.toUnsignedLong(getInt(toAddr(0x100785c8L)));
        long inhibit = Integer.toUnsignedLong(getInt(toAddr(0x100785ccL)));
        for (int flags = 0; flags < 128; flags++) {
            var e = emulator();
            try {
                object(e, 3); e.writeMemory(toAddr(motor), new byte[0x20]); value(e, inhibit, 1, 0);
                value(e, CAP, 1, 80); value(e, APP + 0x118, 1, 20);
                e.writeMemory(toAddr(DATA), new byte[]{(byte)flags, 1, 2, 3, 4, 1, 0, 1});
                e.writeRegister("r0", OBJECT); e.writeRegister("r1", 24); e.writeRegister("r2", 0x1200); e.writeRegister("r3", DATA);
                e.writeStackValue(0, 4, 8); e.writeStackValue(4, 4, 0);
                List<Integer> motors = new ArrayList<>();
                execute(e, 0x10078438L, (pc, em) -> {
                    if (diagnostics(pc, em)) return true;
                    if (pc == 0x10075e64L) {
                        long channel = reg(em, "r0") - channels;
                        if (channel < 0 || channel % 14 != 0 || channel / 14 > 3) throw new AssertionError("Unexpected motor channel");
                        motors.add((int)(channel / 14)); returned(em, 0); return true;
                    }
                    if (pc == 0x001045acL) {
                        if (reg(em, "r1") != 0x6a) throw new AssertionError("Unexpected event from rumble");
                        returned(em, 0); return true;
                    }
                    if (pc == 0x00104674L) { returned(em, 0); return true; }
                    if (pc == 0x1003edfcL || pc == 0x100757fcL || pc == 0x10074ffcL) throw new AssertionError("Unexpected rumble fallback or LED path");
                    return false;
                });
                for (int channel = 0; channel < 4; channel++) if (motors.contains(channel) != ((flags & (1 << channel)) != 0))
                    throw new AssertionError("Rumble flag routing mismatch");
                if (byteAt(e, CAP) != 80 || byteAt(e, APP + 0x118) != 20) throw new AssertionError("Rumble altered LED state");
            } finally { e.dispose(); }
        }
        writer.println("All 128 BLE-reachable rumble flag values: four motor selects only; bits 4-6 add no LED route; brightness and cap unchanged.");
    }

    private void checkBattery(PrintWriter writer) throws Exception {
        long motor = Integer.toUnsignedLong(getInt(toAddr(0x100785bcL)));
        long channels = Integer.toUnsignedLong(getInt(toAddr(0x100785c8L)));
        for (int level = 0; level <= 4; level++) {
            var e = emulator();
            try {
                e.writeMemory(toAddr(motor), new byte[0x20]); value(e, CAP, 1, 80); value(e, APP + 0x118, 1, 20);
                e.writeMemory(toAddr(DATA), new byte[]{0, (byte)level, 0});
                e.writeRegister("r1", DATA); e.writeRegister("r2", 3);
                List<Long> stopped = new ArrayList<>();
                execute(e, 0x100783d4L, (pc, em) -> {
                    if (diagnostics(pc, em)) return true;
                    if (pc == 0x100760b4L) { stopped.add(reg(em, "r0")); returned(em, 0); return true; }
                    return false;
                });
                if (!stopped.equals(level < 2 ? List.of(channels, channels + 14) : List.of())) throw new AssertionError("Unexpected battery motor policy");
                if (byteAt(e, CAP) != 80 || byteAt(e, APP + 0x118) != 20) throw new AssertionError("Battery motor guard altered LED state");
            } finally { e.dispose(); }
        }
        writer.println("Battery guard levels 0-4: low levels stop two grip motors; no LED state or cap write in this callback.");
    }

    private void findConfigurationDispatcher(PrintWriter writer) throws Exception {
        for (var block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized()) continue;
            byte[] bytes = getBytes(block.getStart(), Math.toIntExact(block.getSize()));
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset + 4 <= bytes.length; offset++) {
                if (buffer.getInt(offset) == 0x00102181)
                    writer.println("Configuration dispatcher Thumb pointer at " + block.getStart().add(offset));
            }
        }
    }

    private void checkPwmChannels(PrintWriter writer) throws Exception {
        long table = Integer.toUnsignedLong(getInt(toAddr(0x10075d4cL)));
        int ledGroup = Byte.toUnsignedInt(getByte(toAddr(table)));
        int ledChannel = Byte.toUnsignedInt(getByte(toAddr(table + 1)));
        if (table != 0x100848b8L || ledGroup != 0 || ledChannel != 0x25)
            throw new AssertionError("Unexpected LED PWM mapping");
        for (int motor = 0; motor < 4; motor++) {
            int output = Byte.toUnsignedInt(getByte(toAddr(0x10076210L + motor)));
            if (getByte(toAddr(table + output * 8L)) == ledGroup
                    || getByte(toAddr(table + output * 8L + 1)) == ledChannel)
                throw new AssertionError("Motor and LED share PWM configuration");
        }
        writer.println("Board PWM table: LED group 0, channel 0x25; all four motors use other groups and channels. Analogue supply effects not emulated.");
    }
}
