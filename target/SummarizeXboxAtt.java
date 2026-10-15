// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

class SummarizeXboxAtt {
    record Read(int opcode, int handle, int offset) {}
    static int word(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset]) | (Byte.toUnsignedInt(data[offset + 1]) << 8);
    }
    static String uuid(byte[] data, int offset, int length) {
        if (length == 2) return "%04X".formatted(word(data, offset));
        if (length != 16) throw new IllegalArgumentException("Unsupported UUID length");
        byte[] reversed = new byte[16];
        for (int i = 0; i < 16; i++) reversed[i] = data[offset + 15 - i];
        String hex = HexFormat.of().withUpperCase().formatHex(reversed);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: compact-capture.txt gatt-probe.txt [packet-name-or-address]");
        String packetName = args.length == 3 ? args[2] : "Xbox Wireless Controller";
        var opcodes = new TreeMap<Integer, Integer>();
        var notifications = new TreeMap<Integer, Integer>();
        var values = new TreeMap<Integer, ByteArrayOutputStream>();
        var errors = new ArrayList<String>();
        var schema = new LinkedHashSet<String>();
        Integer reportMapHandle = null;
        int readType = -1;
        Read pending = null;
        int packets = 0, lineNumber = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            lineNumber++;
            String[] fields = line.split("\t", -1);
            if (fields.length < 7 || (!fields[2].equals(packetName) && !fields[2].equals("Xbox Wireless Controller")) || (!fields[1].startsWith("ATT ") && !fields[1].startsWith("ACL "))) continue;
            byte[] data = HexFormat.of().parseHex(fields[6].replace(" ", ""));
            if (data.length < 9 || ((word(data, 0) >> 12) & 3) == 1)
                throw new IllegalStateException("Unsupported fragmented or malformed ACL packet");
            if (word(data, 6) != 4) continue;
            if (word(data, 2) != data.length - 4 || word(data, 4) != data.length - 8)
                throw new IllegalStateException("Unsupported fragmented or malformed ATT packet: " + fields[5]);
            int opcode = Byte.toUnsignedInt(data[8]);
            packets++;
            opcodes.merge(opcode, 1, Integer::sum);
            switch (opcode) {
                case 0x08 -> readType = data.length == 15 ? word(data, 13) : -1;
                case 0x09 -> {
                    int width = Byte.toUnsignedInt(data[9]);
                    if (width < 3 || (data.length - 10) % width != 0) throw new IllegalStateException("Malformed Read By Type response");
                    for (int offset = 10; offset < data.length; offset += width) {
                        var value = new ByteArrayOutputStream();
                        value.write(data, offset + 2, width - 2);
                        values.put(word(data, offset), value);
                        if (readType == 0x2803) {
                            String id = uuid(data, offset + 5, width - 5);
                            int handle = word(data, offset + 3);
                            schema.add("Characteristic uuid=%s value-handle=0x%04x properties=0x%02x".formatted(id, handle, Byte.toUnsignedInt(data[offset + 2])));
                            if (id.equals("2A4B")) reportMapHandle = handle;
                        }
                    }
                }
                case 0x11 -> {
                    int width = Byte.toUnsignedInt(data[9]);
                    if ((width != 6 && width != 20) || (data.length - 10) % width != 0) throw new IllegalStateException("Malformed Read By Group response");
                    for (int offset = 10; offset < data.length; offset += width)
                        schema.add("Service uuid=%s handles=0x%04x-0x%04x".formatted(uuid(data, offset + 4, width - 4), word(data, offset), word(data, offset + 2)));
                }
                case 0x0a, 0x0c -> {
                    if (pending != null) throw new IllegalStateException("Overlapping reads");
                    int handle = word(data, 9), offset = opcode == 0x0c ? word(data, 11) : 0;
                    if (offset == 0) values.put(handle, new ByteArrayOutputStream());
                    pending = new Read(opcode, handle, offset);
                }
                case 0x0b, 0x0d -> {
                    if (pending == null || opcode != pending.opcode() + 1) throw new IllegalStateException("Unmatched read response at line " + lineNumber + ", pending=" + pending);
                    var value = values.get(pending.handle());
                    if (value.size() != pending.offset()) throw new IllegalStateException("Non-contiguous read");
                    value.write(data, 9, data.length - 9);
                    pending = null;
                }
                case 0x01 -> {
                    errors.add("request=0x%02x handle=0x%04x error=0x%02x".formatted(Byte.toUnsignedInt(data[9]), word(data, 10), Byte.toUnsignedInt(data[12])));
                    if (pending != null && Byte.toUnsignedInt(data[9]) == pending.opcode()) pending = null;
                }
                case 0x1b -> notifications.merge(word(data, 9), 1, Integer::sum);
                default -> {}
            }
        }
        if (pending != null) throw new IllegalStateException("Incomplete final read");
        System.out.println("Xbox ATT packets: " + packets);
        schema.forEach(System.out::println);
        opcodes.forEach((opcode, count) -> System.out.printf("Opcode 0x%02x: %d%n", opcode, count));
        notifications.forEach((handle, count) -> System.out.printf("Notifications at 0x%04x: %d%n", handle, count));
        errors.forEach(System.out::println);
        values.forEach((handle, value) -> System.out.printf("Read handle 0x%04x: %d bytes%n", handle, value.size()));
        var handles = Map.of("2A29", 0x000b, "2A50", 0x000d, "2A26", 0x000f, "2A25", 0x0011, "2A19", 0x0014,
                "00000002-5F60-4C4F-9C83-A7953298D40D", 0x0026, "00000003-5F60-4C4F-9C83-A7953298D40D", 0x0028);
        Pattern reading = Pattern.compile("Value: uuid=(\\S+) bytes=(\\d+) error=none data=([0-9a-f ]+)");
        int matches = 0;
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            Matcher match = reading.matcher(line);
            if (!match.matches() || !handles.containsKey(match.group(1))) continue;
            byte[] expected = HexFormat.of().parseHex(match.group(3).replace(" ", ""));
            var actual = values.get(handles.get(match.group(1)));
            if (expected.length != Integer.parseInt(match.group(2)) || actual == null || !Arrays.equals(expected, actual.toByteArray()))
                throw new IllegalStateException("ATT/CoreBluetooth mismatch: " + match.group(1));
            matches++;
        }
        if (matches != handles.size()) throw new IllegalStateException("Missing CoreBluetooth values");
        System.out.println("All " + matches + " characteristic values match CoreBluetooth byte for byte.");
        if (reportMapHandle != null && values.containsKey(reportMapHandle)) {
            byte[] reportMap = values.get(reportMapHandle).toByteArray();
            Path capture = Path.of(args[0]);
            Path output = capture.resolveSibling(capture.getFileName().toString().replace("-compact.txt", "") + "-report-map.bin");
            Files.write(output, reportMap);
            System.out.println("Report Map: " + reportMap.length + " bytes, saved to " + output);
        }
    }
}
