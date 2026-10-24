// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.plugin.core.analysis.PdbUniversalAnalyzer;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.scalar.Scalar;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** Static Windows driver inspection with matching Microsoft public symbols. */
public class XboxWindows extends GhidraScript {
    @Override protected void run() throws Exception {
        String[] args = getScriptArgs();
        Path output = Path.of(args[1]);
        if (args[0].equals("prepare")) {
            String pdb = switch (currentProgram.getName().toLowerCase()) {
                case "xinputhid.sys" -> "XINPUTHID.pdb";
                case "xboxgip.sys" -> "XBOXGIP.pdb";
                case "dc1-controller.sys" -> "dc1-controller.pdb";
                case "windows.gaming.input.dll" -> "Windows.Gaming.Input.pdb";
                case "microsoft.bluetooth.profiles.hidovergatt.dll" -> "Microsoft.Bluetooth.Profiles.HidOverGatt.pdb";
                default -> throw new IllegalArgumentException("Unexpected Windows binary");
            };
            Path file = output.resolve(pdb);
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing PDB: " + file);
            PdbUniversalAnalyzer.setPdbFileOption(currentProgram, file.toFile());
            return;
        }

        Files.createDirectories(output);
        String prefix = currentProgram.getName();
        if (args[0].equals("routes")) {
            // shortcut: this lists direct constants; computed IOCTLs and external consumers require caller tracing or runtime capture.
            Set<Long> controls = Set.of(0x40001c14L, 0x40001c18L, 0x40001c1cL, 0x40001c60L, 0x40001cb8L);
            int instructions = 0, matches = 0;
            try (var routes = new PrintWriter(Files.newBufferedWriter(output.resolve(prefix + ".routes.tsv")))) {
                for (var instruction : currentProgram.getListing().getInstructions(true)) {
                    instructions++;
                    for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                        for (var object : instruction.getOpObjects(operand)) {
                            if (!(object instanceof Scalar scalar) || !controls.contains(scalar.getUnsignedValue())) continue;
                            Function owner = getFunctionContaining(instruction.getAddress());
                            routes.printf("IOCTL\t%08x\t%s\t%s\t%s%n", scalar.getUnsignedValue(), instruction.getAddress(),
                                    owner == null ? "unknown" : owner.getName(true), instruction);
                            matches++;
                        }
                    }
                }
                for (var function : currentProgram.getFunctionManager().getExternalFunctions())
                    routes.printf("IMPORT\t%s%n", function.getName(true));
                if (instructions == 0) throw new AssertionError("No instructions available for route inspection");
                routes.printf("COMPLETE\tinstructions=%d\timmediate_matches=%d%n", instructions, matches);
            }
            println("Inspected " + instructions + " instructions for direct IOCTL constants in " + prefix);
            return;
        }
        var pattern = Pattern.compile(args.length > 2 ? args[2]
                : "Led|LED|Intensity|Brightness|DispatchSetGamepadState|DispatchXINPUTIoctl|_SendVibrationMessage|OnIoDeviceControl|gipIoControl|gipMessageThread|gipForwardUsbTestCommand|DccEvtIoDeviceControl|DccUsbFw(ReadEEProm|WaitForCompletion_App|SendCommand)|HogpIo::HidSetReport$|HogpGattAdapter::WriteReportCharacteristicValueAsync$");
        var decompiler = new DecompInterface();
        if (!decompiler.openProgram(currentProgram)) throw new IllegalStateException("Cannot open decompiler");
        int selected = 0;
        try (var functions = new PrintWriter(Files.newBufferedWriter(output.resolve(prefix + ".functions.tsv")));
             var code = new PrintWriter(Files.newBufferedWriter(output.resolve(prefix + ".controls.c")))) {
            for (Function function : currentProgram.getFunctionManager().getFunctions(true)) {
                functions.printf("%s\t%s\t%d%n", function.getEntryPoint(), function.getName(true), function.getBody().getNumAddresses());
                if (!pattern.matcher(function.getName(true)).find()) continue;
                selected++;
                code.printf("\n/* %s %s */%n", function.getEntryPoint(), function.getName(true));
                var result = decompiler.decompileFunction(function, 60, monitor);
                if (!result.decompileCompleted()) throw new IllegalStateException("Decompile failed: " + function.getName());
                code.println(result.getDecompiledFunction().getC());
                for (Function caller : function.getCallingFunctions(monitor))
                    code.printf("CALLER %s %s%n", caller.getEntryPoint(), caller.getName(true));
                for (Function callee : function.getCalledFunctions(monitor))
                    code.printf("CALLEE %s %s%n", callee.getEntryPoint(), callee.getName(true));
                for (var instruction : currentProgram.getListing().getInstructions(function.getBody(), true))
                    code.printf("%s  %s%n", instruction.getAddress(), instruction);
            }
            if (selected == 0) throw new AssertionError("No matching named functions; verify PDB loading and pattern");
        } finally { decompiler.dispose(); }
        println("Exported " + selected + " selected functions for " + prefix);
    }
}
