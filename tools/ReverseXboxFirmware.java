// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

class ReverseXboxFirmware {
    static final Path IMAGE = Path.of("target/firmware/MERL-5.23.6.0/component-3.bin");
    static final String HASH = "42e171de2834c4481177488f350cfc82e6fdae48a04137ac5ed468f6fae679b0";

    static byte[] unpack(byte[] input) {
        byte[] output = new byte[256 * 1024];
        int source = 0, destination = 0;
        try {
            while (source < input.length) {
                int token = Byte.toUnsignedInt(input[source++]);
                int literals = token & 3;
                if (literals == 0) literals = Byte.toUnsignedInt(input[source++]) + 3;
                int match = token >>> 4;
                if (match == 15) match += Byte.toUnsignedInt(input[source++]);
                for (int i = 1; i < literals; i++) output[destination++] = input[source++];
                if (match == 0) continue;
                int high = (token >>> 2) & 3;
                int low = Byte.toUnsignedInt(input[source++]);
                if (high == 3) high = Byte.toUnsignedInt(input[source++]);
                int distance = low + high * 256;
                if (distance == 0 || distance > destination) throw new IllegalArgumentException("Invalid startup back-reference");
                for (int i = 0; i < match + 2; i++) {
                    output[destination] = output[destination - distance];
                    destination++;
                }
            }
        } catch (IndexOutOfBoundsException invalid) {
            throw new IllegalArgumentException("Invalid or excessive startup compressed data", invalid);
        }
        return Arrays.copyOf(output, destination);
    }

    static void unpackStartup(byte[] image, Path output) throws Exception {
        var data = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        for (int table : new int[]{0x5fab4, 0x5fac4}) {
            if (0x10027000L + table + data.getInt(table) != 0x1002c7afL)
                throw new IllegalArgumentException("Unexpected startup decompression handler");
            int source = table + 4 + data.getInt(table + 4);
            int encodedSize = data.getInt(table + 8);
            int address = data.getInt(table + 12);
            if ((encodedSize & 1) != 0 || source < 0 || source + (encodedSize >>> 1) > image.length)
                throw new IllegalArgumentException("Invalid startup record");
            byte[] unpacked = unpack(Arrays.copyOfRange(image, source, source + (encodedSize >>> 1)));
            if (address != (table == 0x5fab4 ? 0x00100000 : 0x20004010))
                throw new IllegalArgumentException("Unexpected startup destination");
            Path file = output.resolve(table == 0x5fab4 ? "startup-code.bin" : "startup-data.bin");
            Files.write(file, unpacked);
            System.out.printf("Reconstructed %s: source 0x%08x, destination 0x%08x, %d bytes, SHA-256 %s%n",
                    file, source + 0x10027000L, address, unpacked.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unpacked)));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !List.of("import", "map-startup", "handlers", "inspect", "--self-test").contains(args[0]))
            throw new IllegalArgumentException("Usage: ReverseXboxFirmware.java import | map-startup | handlers | inspect [ADDRESS...] | --self-test");

        byte[] bytes = Files.readAllBytes(IMAGE);
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!HASH.equals(hash) || data.getInt(0) != 0x20030d90 || data.getInt(4) != 0x100271c1)
            throw new IllegalArgumentException("Expected verified MERL 5.23.6.0 application image");
        if (args[0].equals("--self-test")) {
            if (!Arrays.equals(unpack(new byte[]{3, 65, 66, 0x12, 67, 3}), new byte[]{65, 66, 67, 65, 66, 67})
                    || !Arrays.equals(unpack(new byte[]{0, 0, 65, 66}), new byte[]{65, 66}))
                throw new AssertionError("Startup decompressor self-test failed");
            for (byte[] invalid : List.of(new byte[]{0}, new byte[]{3, 65}, new byte[]{0x11, 1}, new byte[]{0x11, 0})) {
                try {
                    unpack(invalid);
                    throw new AssertionError("Accepted invalid startup stream");
                } catch (IllegalArgumentException expected) {
                    // Expected malformed input rejection.
                }
            }
            System.out.println("Verified application SHA-256, vectors, startup literals, overlapping back-references and four malformed streams.");
            return;
        }

        Path installation = Path.of(System.getenv().getOrDefault("GHIDRA_INSTALL_DIR", "/opt/homebrew/opt/ghidra/libexec"));
        Path utility = installation.resolve("Ghidra/Framework/Utility/lib/Utility.jar");
        if (!Files.isRegularFile(utility)) throw new IllegalArgumentException("Set GHIDRA_INSTALL_DIR to a Ghidra installation");
        Path output = Path.of("target/firmware-analysis").toAbsolutePath();
        Path projects = output.resolve("projects");
        Files.createDirectories(projects);
        unpackStartup(bytes, output);
        // Launch directly so Ghidra's JDK selector does not save settings outside this checkout.
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx2G", "-Xshare:off", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED",
                "-Djava.system.class.loader=ghidra.GhidraClassLoader", "-Dfile.encoding=UTF-8",
                "-Dapplication.settingsdir=" + output.resolve("settings"),
                "-Dapplication.cachedir=" + output.resolve("cache"), "-cp", utility.toString(),
                "ghidra.Ghidra", "ghidra.app.util.headless.AnalyzeHeadless", projects.toString(), "MERL-5.23.6.0",
                "-scriptPath", Path.of("tools/ghidra").toAbsolutePath().toString(), "-max-cpu", "4"));
        if (args[0].equals("import")) {
            if (Files.exists(projects.resolve("MERL-5.23.6.0.gpr")))
                throw new IllegalArgumentException("Analysis project already exists; use inspect");
            command.addAll(List.of("-import", IMAGE.toAbsolutePath().toString(), "-loader", "BinaryLoader",
                    "-loader-baseAddr", "0x10027000", "-processor", "ARM:LE:32:Cortex", "-cspec", "default",
                    "-analysisTimeoutPerFile", "300", "-preScript", "XboxFirmware.java", "prepare",
                    "-postScript", "XboxFirmware.java", "export", output.toString()));
        } else if (List.of("map-startup", "handlers").contains(args[0])) {
            command.addAll(List.of("-process", "component-3.bin", "-analysisTimeoutPerFile", "300",
                    "-preScript", "XboxFirmware.java", args[0], output.toString(),
                    "-postScript", "XboxFirmware.java", "export", output.toString()));
        } else {
            command.addAll(List.of("-process", "component-3.bin", "-readOnly", "-noanalysis",
                    "-postScript", "XboxFirmware.java", "inspect", output.toString()));
            command.addAll(List.of(args).subList(1, args.length));
        }
        var process = new ProcessBuilder(command).inheritIO();
        int status = process.start().waitFor();
        if (status != 0) throw new IllegalStateException("Ghidra exited with status " + status);
    }
}
