// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

class FetchXboxFirmware {
    // Pinned to the verified MERL package; update the URL and hash together for another release.
    static final String HASH = "8c2fb2ee745646d4021d00b28b6aefeb1b50a608a433a246d2bea5604dc0e693";
    static final URI URL = URI.create("https://xbaccessories-bhf7bsbfgxdpbrg4.b02.azurefd.net/accessories/FW/"
            + "MERL_045E_0B12_v_5_23_6_0_h_jC-y7nRWRtQCHQCyi2rv6xtQpgikM6JG0r6lYE3A5pM=.bin");
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static void verifyHash(byte[] data) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!HASH.equals(hash)) throw new IllegalArgumentException("Firmware SHA-256 mismatch: " + hash);
    }

    static String version(ByteBuffer data, int offset) {
        return "%d.%d.%d.%d".formatted(Short.toUnsignedInt(data.getShort(offset)),
                Short.toUnsignedInt(data.getShort(offset + 2)), Short.toUnsignedInt(data.getShort(offset + 4)),
                Short.toUnsignedInt(data.getShort(offset + 6)));
    }

    static ByteBuffer packageData(byte[] bytes) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 0x40 || data.getInt(0) != 0x6d686472 || data.getInt(4) != 1
                || Short.toUnsignedInt(data.getShort(8)) != 0x045e
                || Short.toUnsignedInt(data.getShort(10)) != 0x0b12 || !version(data, 0x0c).equals("5.23.6.0"))
            throw new IllegalArgumentException("Expected MERL 045e:0b12 rdhm package version 5.23.6.0");
        int count = data.getInt(0x1c);
        if (count < 1 || count > 10 || bytes.length < 0x40 + count * 0x40)
            throw new IllegalArgumentException("Invalid component table");
        long total = 0x40 + count * 0x40;
        for (int i = 0; i < count; i++) {
            int entry = 0x40 + i * 0x40;
            long offset = Integer.toUnsignedLong(data.getInt(entry + 8));
            long size = Integer.toUnsignedLong(data.getInt(entry + 12));
            if (offset < 0x40 + count * 0x40 || size == 0 || offset + size > bytes.length)
                throw new IllegalArgumentException("Component outside package: " + i);
            total += size;
        }
        if (total != bytes.length) throw new IllegalArgumentException("Package size does not match components");
        return data;
    }

    static void save(Path path, byte[] bytes) throws Exception {
        if (Files.exists(path)) {
            if (!Arrays.equals(Files.readAllBytes(path), bytes))
                throw new IllegalArgumentException("Refusing to replace different file: " + path);
        } else {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        }
    }

    static void selfTest() throws Exception {
        var data = ByteBuffer.allocate(0x84).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, 0x6d686472).putInt(4, 1).putShort(8, (short) 0x045e).putShort(10, (short) 0x0b12);
        data.putShort(0x0c, (short) 5).putShort(0x0e, (short) 23).putShort(0x10, (short) 6);
        data.putInt(0x1c, 1).putInt(0x48, 0x80).putInt(0x4c, 4);
        packageData(data.array());
        reject(new byte[0]);
        reject(Arrays.copyOf(data.array(), 0x70));
        for (int offset : new int[]{0, 4, 8, 10, 0x0c, 0x1c, 0x48, 0x4c}) {
            byte[] invalid = data.array().clone();
            invalid[offset] ^= 1;
            reject(invalid);
        }
        try {
            verifyHash(data.array());
        } catch (IllegalArgumentException expected) {
            System.out.println("Self-test passed: valid package layout, ten invalid layouts, and wrong SHA-256.");
            return;
        }
        throw new AssertionError("Accepted an incorrect firmware hash");
    }

    static void reject(byte[] bytes) {
        try {
            packageData(bytes);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted an invalid package");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.printf("""
                    Usage: FetchXboxFirmware.java [OUTPUT_DIRECTORY] | --help | --self-test

                    Download and extract the pinned Microsoft MERL package 5.23.6.0
                    for USB 045e:0b12. This tool does not access a controller or flash firmware.
                    Default output: target/firmware/MERL-5.23.6.0

                    Source: %s
                    Expected SHA-256: %s

                    Cached package.bin files are verified before extraction, so a cached run
                    works offline. Existing files with different contents are never replaced.
                    The tool checks the pinned hash and package layout; it does not verify
                    the embedded Microsoft signatures or select the latest release.

                    If automatic downloading fails, obtain the complete package manually:
                      mkdir -p target/firmware/MERL-5.23.6.0
                      curl --fail --location '%s' --output target/firmware/MERL-5.23.6.0/package.bin
                      mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java

                    Official feed indexes (for checking release metadata):
                      https://aka.ms/xboxaccessoriesfwcdnpcga
                      https://aka.ms/xboxaccessoriesfwcdnxbga
                    Keep the URL, expected hash and package version pinned together.
                    """, URL, HASH, URL);
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length > 1 || (args.length == 1 && args[0].startsWith("--")))
            throw new IllegalArgumentException("Usage: FetchXboxFirmware.java [OUTPUT_DIRECTORY] | --help | --self-test");

        Path directory = Path.of(args.length == 0 ? "target/firmware/MERL-5.23.6.0" : args[0]);
        Path packageFile = directory.resolve("package.bin");
        byte[] bytes;
        if (Files.exists(packageFile)) {
            if (Files.size(packageFile) > MAX_BYTES) throw new IllegalArgumentException("Cached package too large");
            bytes = Files.readAllBytes(packageFile);
        } else {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
                var request = HttpRequest.newBuilder(URL).timeout(Duration.ofSeconds(45)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (response.statusCode() != 200) throw new IllegalStateException("Download HTTP " + response.statusCode());
                    bytes = body.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Downloaded package too large");
                }
            }
        }
        verifyHash(bytes);
        var data = packageData(bytes);
        Files.createDirectories(directory);
        save(packageFile, bytes);
        System.out.printf("Verified %s: %d bytes, SHA-256 %s%n", packageFile, bytes.length, HASH);
        System.out.println("Source: " + URL);
        for (int i = 0; i < data.getInt(0x1c); i++) {
            int entry = 0x40 + i * 0x40;
            int id = data.getInt(entry), offset = data.getInt(entry + 8), size = data.getInt(entry + 12);
            Path output = directory.resolve("component-" + Integer.toUnsignedString(id) + ".bin");
            save(output, Arrays.copyOfRange(bytes, offset, offset + size));
            System.out.printf("%s: version %s, address 0x%08x, %d bytes, flags 0x%x%n",
                    output, version(data, entry + 0x18), data.getInt(entry + 0x10), size, data.getInt(entry + 4));
        }
    }
}
