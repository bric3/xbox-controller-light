// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Offline analysis of the pinned MERL application, launched by ReverseXboxFirmware.java.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;
import ghidra.app.emulator.EmulatorHelper;
import java.io.PrintWriter;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public class XboxFirmware extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args[0].equals("prepare")) {
            var memory = currentProgram.getMemory();
            memory.getBlock(toAddr(0x10027000L)).setName("FLASH_APP");
            var ram = memory.createUninitializedBlock("RAM", toAddr(0x20000000L), 0x40000, false);
            ram.setRead(true);
            ram.setWrite(true);
            ram.setExecute(false);
            currentProgram.getProgramContext().setValue(currentProgram.getRegister("TMode"),
                    toAddr(0x10027000L), toAddr(0x100a4fffL), BigInteger.ONE);
            for (int i = 1; i < 80; i++) {
                long pointer = Integer.toUnsignedLong(getInt(toAddr(0x10027000L + 4L * i)));
                if ((pointer & 1) == 0 || pointer < 0x10027000L || pointer >= 0x100a5000L) continue;
                Address entry = toAddr(pointer & ~1L);
                disassemble(entry);
                if (getFunctionAt(entry) == null) createFunction(entry, "vector_" + i);
                currentProgram.getSymbolTable().addExternalEntryPoint(entry);
            }
            return;
        }

        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        if (args[0].equals("handlers")) {
            // These names describe recovered roles; the firmware contains no original function symbols.
            long[] addresses = {0x1004ac04L, 0x10074ffcL, 0x100756f6L, 0x100757fcL, 0x10075b48L,
                    0x00103544L, 0x1004bd54L, 0x00106864L, 0x1004bdd0L, 0x1004c56aL,
                    0x1004c5caL, 0x00106cf8L, 0x00107498L, 0x1004c324L, 0x1004c390L,
                    0x1004c406L, 0x1004c4c8L, 0x1004c514L};
            String[] names = {"gip_led_command", "led_task_dispatch", "led_pwm_dispatch", "led_clamp_brightness", "led_write_pwm",
                    "host_manager_dispatch", "host_state_0", "host_state_1", "host_state_2", "host_state_3",
                    "host_state_4", "host_state_3_wireless", "host_state_3_usb", "host_enrol_state_0", "host_enrol_state_1",
                    "host_enrol_state_2", "host_enrol_state_3", "host_enrol_state_4"};
            for (int i = 0; i < addresses.length; i++) callback(addresses[i] | 1L, names[i]);
            try (var table = new PrintWriter(Files.newBufferedWriter(output.resolve("gatt-handlers.tsv")))) {
                for (int i = 0; i < 26; i++) {
                    Address entry = toAddr(0x20004c5cL + i * 28L);
                    Address uuidAddress = toAddr(Integer.toUnsignedLong(getInt(entry.add(4))));
                    String uuid = new String(getBytes(uuidAddress, 40), StandardCharsets.US_ASCII);
                    uuid = uuid.substring(0, uuid.indexOf('\0'));
                    long callback = Integer.toUnsignedLong(getInt(entry.add(12)));
                    table.printf("%s\t%d\t%s\t0x%04x\t%08x\n", entry, getByte(entry), uuid,
                            Short.toUnsignedInt(getShort(entry.add(8))), callback);
                    if (callback != 0) callback(callback, "gatt_" + uuid.replace('-', '_'));
                }
            }
            try (var table = new PrintWriter(Files.newBufferedWriter(output.resolve("gip-handlers.tsv")))) {
                for (int i = 0; i < 32; i++) {
                    Address entry = toAddr(0x20004638L + i * 10L);
                    int command = Short.toUnsignedInt(getShort(entry));
                    if (command == 0) break;
                    long callback = Integer.toUnsignedLong(getInt(entry.add(2)));
                    table.printf("%s\t%04x\t%08x\t%04x\n", entry, command, callback,
                            Short.toUnsignedInt(getShort(entry.add(6))));
                    if (callback != 0) callback(callback, command == 0x200a ? "gip_led_command" : "gip_%04x".formatted(command));
                }
            }
            try (var verification = new PrintWriter(Files.newBufferedWriter(output.resolve("startup-verification.txt")))) {
                for (long record : new long[]{0x10086ab8L, 0x10086ac8L}) {
                    long destination = Integer.toUnsignedLong(getInt(toAddr(record + 8)));
                    byte[] expected = Files.readAllBytes(output.resolve(destination == 0x00100000L ? "startup-code.bin" : "startup-data.bin"));
                    var emulator = new EmulatorHelper(currentProgram);
                    try {
                        for (int register = 0; register < 13; register++) emulator.writeRegister("r" + register, 0);
                        emulator.writeRegister("r0", record);
                        emulator.writeRegister("sp", 0x2003f000L);
                        emulator.writeRegister("lr", 0x100a4f01L);
                        emulator.writeRegister("r9", 0);
                        emulator.writeMemory(toAddr(destination), new byte[expected.length]);
                        // Stop before POP returns; Ghidra 12.1.4's legacy ARM emulator fails its ISA switch there.
                        emulator.setBreakpoint(toAddr(0x1002c826L));
                        if (!emulator.run(toAddr(0x1002c7aeL), null, monitor))
                            throw new AssertionError("Startup emulation failed: " + emulator.getLastError());
                        if (!Arrays.equals(expected, emulator.readMemory(toAddr(destination), expected.length)))
                            throw new AssertionError("Startup emulation differs from Java decoder");
                        verification.printf("Firmware decompressor agrees with Java decoder: 0x%08x, %d bytes\n", destination, expected.length);
                        verification.flush();
                    } finally {
                        emulator.dispose();
                    }
                }
            }
            try (var verification = new PrintWriter(Files.newBufferedWriter(output.resolve("gatt-verification.txt")))) {
                var emulator = new EmulatorHelper(currentProgram);
                try {
                    for (int register = 0; register < 13; register++) emulator.writeRegister("r" + register, 0);
                    emulator.writeRegister("sp", 0x2003f000L);
                    emulator.writeRegister("r0", 1);
                    emulator.writeRegister("r1", 0x20038000L);
                    emulator.setBreakpoint(toAddr(0x10040a06L));
                    if (!emulator.run(toAddr(0x100409ecL), null, monitor))
                        throw new AssertionError("Vendor GATT write emulation failed: " + emulator.getLastError());
                    if (emulator.readRegister("r0").intValue() != 0x18
                            || emulator.readRegister("r1").intValue() != 0x1d
                            || emulator.readRegister("r2").intValue() != 0x28
                            || emulator.readRegister("r3").longValue() != 0x2003802aL
                            || emulator.readStackValue(0, 4, false).intValue() != 7
                            || emulator.readStackValue(4, 4, false).intValue() != 0)
                        throw new AssertionError("Unexpected vendor GATT write destination");
                    verification.println("Vendor GATT write queues seven bytes to task 29, command 0x28; no GIP opcode dispatch.");
                } finally {
                    emulator.dispose();
                }
            }
            try (var verification = new PrintWriter(Files.newBufferedWriter(output.resolve("led-verification.txt")))) {
                for (int[] test : new int[][]{{3, 0, 1, 10}, {3, 0, 0, 0}, {3, 0, 1, 20},
                        {3, 0, 1, 50}, {3, 0, 1, 255}, {0, 0, 1, 10}, {3, 1, 1, 10}}) {
                    var emulator = new EmulatorHelper(currentProgram);
                    try {
                        for (int register = 0; register < 13; register++) emulator.writeRegister("r" + register, 0);
                        emulator.writeRegister("sp", 0x2003f000L);
                        emulator.writeRegister("r0", 0x20038000L);
                        long state = Integer.toUnsignedLong(getInt(toAddr(0x1004b484L)));
                        emulator.writeMemoryValue(toAddr(state + 4), 1, 3);
                        emulator.writeMemoryValue(toAddr(state + 0x118), 1, 0xa5);
                        emulator.writeMemory(toAddr(0x20038000L), new byte[32]);
                        emulator.writeMemoryValue(toAddr(0x20038006L), 4, test[0]);
                        emulator.writeMemoryValue(toAddr(0x20038012L), 4, 0x20038100L);
                        emulator.writeMemory(toAddr(0x20038100L), new byte[]{(byte) test[1], (byte) test[2], (byte) test[3]});
                        emulator.setBreakpoint(toAddr(0x1004ac3eL));
                        emulator.setBreakpoint(toAddr(0x1004ac42L));
                        if (!emulator.run(toAddr(0x1004ac04L), null, monitor))
                            throw new AssertionError("LED handler emulation failed: " + emulator.getLastError());
                        boolean accepted = test[0] != 0 && test[1] == 0;
                        if (accepted) {
                            if (!emulator.getExecutionAddress().equals(toAddr(0x1004ac3eL))
                                    || emulator.readRegister("r1").intValue() != 8
                                    || emulator.readRegister("r2").intValue() != (0xb00 | test[2])
                                    || emulator.readRegister("r3").longValue() != state + 0x118
                                    || emulator.readStackValue(0, 4, false).intValue() != 1
                                    || Byte.toUnsignedInt(emulator.readMemoryByte(toAddr(state + 0x118))) != test[3])
                                throw new AssertionError("LED command did not produce the expected LED-task message");
                        } else if (!emulator.getExecutionAddress().equals(toAddr(0x1004ac42L))
                                || Byte.toUnsignedInt(emulator.readMemoryByte(toAddr(state + 0x118))) != 0xa5) {
                            throw new AssertionError("Ignored LED packet changed state");
                        }
                        verification.printf("length=%d, reserved=%d, mode=%d, brightness=%d: %s\n",
                                test[0], test[1], test[2], test[3], accepted ? "LED-task message verified" : "ignored without state change");
                        verification.flush();
                    } finally {
                        emulator.dispose();
                    }
                }
            }
            return;
        }
        if (args[0].equals("map-startup")) {
            var memory = currentProgram.getMemory();
            byte[] code = Files.readAllBytes(output.resolve("startup-code.bin"));
            byte[] data = Files.readAllBytes(output.resolve("startup-data.bin"));
            if (memory.getBlock(toAddr(0x00100000L)) != null) throw new IllegalStateException("Startup code already mapped");
            var codeBlock = memory.createInitializedBlock("STARTUP_CODE", toAddr(0x00100000L),
                    new ByteArrayInputStream(code), code.length, monitor, false);
            codeBlock.setExecute(true);
            codeBlock.setWrite(false);
            memory.split(memory.getBlock(toAddr(0x20000000L)), toAddr(0x20004010L));
            memory.split(memory.getBlock(toAddr(0x20004010L)), toAddr(0x20004010L + data.length));
            var dataBlock = memory.convertToInitialized(memory.getBlock(toAddr(0x20004010L)), (byte) 0);
            memory.setBytes(dataBlock.getStart(), data);
            dataBlock.setName("STARTUP_DATA");
            currentProgram.getProgramContext().setValue(currentProgram.getRegister("TMode"),
                    codeBlock.getStart(), codeBlock.getEnd(), BigInteger.ONE);
            println("Mapped decompressed executable and initialised data from startup records.");
            return;
        }
        if (args[0].equals("export")) {
            try (var functions = new PrintWriter(Files.newBufferedWriter(output.resolve("functions.tsv")));
                 var references = new PrintWriter(Files.newBufferedWriter(output.resolve("string-references.tsv")));
                 var code = new PrintWriter(Files.newBufferedWriter(output.resolve("decompiled.c")));
                 var instructions = new PrintWriter(Files.newBufferedWriter(output.resolve("instructions.txt")))) {
                var decompiler = new DecompInterface();
                try {
                    if (!decompiler.openProgram(currentProgram)) throw new IllegalStateException(decompiler.getLastMessage());
                    for (var function : currentProgram.getFunctionManager().getFunctions(true)) {
                        monitor.checkCancelled();
                        functions.printf("%s\t%s\t%d\n", function.getEntryPoint(), function.getName(), function.getBody().getNumAddresses());
                        exportFunction(function, decompiler, code);
                    }
                } finally {
                    decompiler.dispose();
                }
                for (var item : currentProgram.getListing().getDefinedData(true)) {
                    if (!item.hasStringValue()) continue;
                    String value = item.getValue().toString().replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                    for (var reference : getReferencesTo(item.getAddress())) {
                        var owner = getFunctionContaining(reference.getFromAddress());
                        references.printf("%s\t%s\t%s\t%s\n", item.getAddress(), reference.getFromAddress(),
                                owner == null ? "data" : owner.getEntryPoint().toString(), value);
                    }
                }
                for (var instruction : currentProgram.getListing().getInstructions(true))
                    instructions.printf("%s  %s\n", instruction.getAddress(), instruction);
            }
            println("Exported function index, decompiled C, string references and instructions to " + output);
        } else if (args[0].equals("inspect")) {
            var decompiler = new DecompInterface();
            try (var writer = new PrintWriter(Files.newBufferedWriter(output.resolve("inspection.txt")))) {
                if (!decompiler.openProgram(currentProgram)) throw new IllegalStateException(decompiler.getLastMessage());
                var seen = new HashSet<Address>();
                for (int i = 2; i < args.length; i++) {
                    String value = args[i].replaceFirst("^code:", "").replaceFirst("^0x", "");
                    Address address = toAddr(Long.parseUnsignedLong(value, 16));
                    var function = getFunctionContaining(address);
                    if (function == null && args[i].startsWith("code:")) {
                        disassemble(address);
                        function = createFunction(address, null);
                    }
                    if (function != null && seen.add(function.getEntryPoint())) {
                        exportFunction(function, decompiler, writer);
                        for (var caller : function.getCallingFunctions(monitor))
                            writer.printf("CALLER %s %s\n", caller.getEntryPoint(), caller.getName());
                        for (var callee : function.getCalledFunctions(monitor))
                            writer.printf("CALLEE %s %s\n", callee.getEntryPoint(), callee.getName());
                        for (var instruction : currentProgram.getListing().getInstructions(function.getBody(), true))
                            writer.printf("%s  %s\n", instruction.getAddress(), instruction);
                    }
                    writer.printf("REFERENCES TO %s\n", address);
                    for (var reference : getReferencesTo(address)) writer.println(reference);
                    var block = currentProgram.getMemory().getBlock(address);
                    if (block != null && block.isInitialized()) {
                        byte[] bytes = new byte[(int) Math.min(128, block.getEnd().subtract(address) + 1)];
                        currentProgram.getMemory().getBytes(address, bytes);
                        writer.printf("MEMORY %s %s\n", address, HexFormat.ofDelimiter(" ").formatHex(bytes));
                    }
                }
            } finally {
                decompiler.dispose();
            }
            println("Wrote inspection.txt");
        } else {
            throw new IllegalArgumentException("Unknown analysis action: " + args[0]);
        }
    }

    private void exportFunction(Function function, DecompInterface decompiler, PrintWriter writer) throws Exception {
        writer.printf("\n/* FUNCTION %s %s */\n", function.getEntryPoint(), function.getName());
        var result = decompiler.decompileFunction(function, 20, monitor);
        if (result.decompileCompleted() && result.getDecompiledFunction() != null)
            writer.println(result.getDecompiledFunction().getC());
        else writer.println("/* DECOMPILATION FAILED: " + result.getErrorMessage() + " */");
        if (writer.checkError()) throw new IllegalStateException("Could not write decompiler output");
    }

    private void callback(long pointer, String name) throws Exception {
        Address entry = toAddr(pointer & ~1L);
        var block = currentProgram.getMemory().getBlock(entry);
        if ((pointer & 1) == 0 || block == null || !block.isExecute())
            throw new IllegalArgumentException("Invalid handler pointer: " + entry);
        disassemble(entry);
        var function = getFunctionAt(entry);
        if (function == null) function = createFunction(entry, name);
        if (function == null) throw new IllegalStateException("Could not define handler: " + entry);
        function.setName(name, SourceType.USER_DEFINED);
    }
}
