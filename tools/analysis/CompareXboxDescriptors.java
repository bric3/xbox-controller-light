// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

class CompareXboxDescriptors {
    static byte[] descriptor(Path path) throws Exception {
        if (path.toString().endsWith(".bin")) return Files.readAllBytes(path);
        String text = Files.readString(path);
        var live = Pattern.compile("(?m)^HID report descriptor \\((\\d+) bytes\\): (.+)$").matcher(text);
        if (live.find()) {
            byte[] bytes = HexFormat.of().parseHex(live.group(2).replace(" ", ""));
            if (bytes.length != Integer.parseInt(live.group(1))) throw new IllegalArgumentException("Invalid live dump length");
            return bytes;
        }
        String hex = text.lines().filter(line -> line.matches("[0-9a-fA-F]{2}( [0-9a-fA-F]{2})*")).reduce("", String::concat).replace(" ", "");
        if (hex.isEmpty()) throw new IllegalArgumentException("No descriptor in " + path);
        return HexFormat.of().parseHex(hex);
    }
    static Map<String, Set<Integer>> reports(byte[] bytes) {
        Map<String, Set<Integer>> reports = new TreeMap<>();
        int reportId = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        for (int offset = 0; offset < bytes.length;) {
            int prefix = Byte.toUnsignedInt(bytes[offset++]);
            if (prefix == 0xfe) {
                int length = Byte.toUnsignedInt(bytes[offset]);
                offset += 2 + length;
                continue;
            }
            int size = (prefix & 3) == 3 ? 4 : prefix & 3;
            if (offset + size > bytes.length) throw new IllegalArgumentException("Truncated HID item");
            if (prefix == 0x85) reportId = Byte.toUnsignedInt(bytes[offset]);
            if (prefix == 0xa4) stack.push(reportId);
            if (prefix == 0xb4) reportId = stack.pop();
            if (((prefix >> 2) & 3) == 0) {
                String kind = switch (prefix & 0xf0) {
                    case 0x80 -> "input";
                    case 0x90 -> "output";
                    case 0xb0 -> "feature";
                    default -> null;
                };
                if (kind != null) reports.computeIfAbsent(kind, unused -> new TreeSet<>()).add(reportId);
            }
            offset += size;
        }
        return reports;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: CompareXboxDescriptors.java LIVE REFERENCE...");
        byte[] live = descriptor(Path.of(args[0]));
        System.out.println("Live descriptor: " + live.length + " bytes, reports=" + reports(live));
        System.out.println("SHA-256: " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(live)));
        for (int i = 1; i < args.length; i++) {
            byte[] reference = descriptor(Path.of(args[i]));
            System.out.println(args[i] + ": " + reference.length + " bytes, identical=" + Arrays.equals(live, reference) + ", reports=" + reports(reference));
            if (!Arrays.equals(live, reference)) System.out.println("First differing byte offset: " + Arrays.mismatch(live, reference));
        }
    }
}
