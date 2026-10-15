// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

class CheckXboxAnalysis {
    public static void main(String[] args) throws Exception {
        Path path = Path.of("BLUETOOTH-MACOS.md");
        List<String> lines = Files.readAllLines(path);
        List<String> formatted = new ArrayList<>();
        int tables = 0;
        for (int i = 0; i < lines.size();) {
            if (!lines.get(i).startsWith("|")) { formatted.add(lines.get(i++)); continue; }
            List<String[]> rows = new ArrayList<>();
            while (i < lines.size() && lines.get(i).startsWith("|")) {
                String[] parts = lines.get(i++).split("(?<!\\\\)\\|", -1);
                if (!parts[0].isBlank() || !parts[parts.length - 1].isBlank()) throw new IllegalStateException("Invalid table boundary");
                rows.add(Arrays.stream(parts, 1, parts.length - 1).map(String::strip).toArray(String[]::new));
            }
            if (rows.size() < 2) throw new IllegalStateException("Missing table separator");
            int[] widths = new int[rows.getFirst().length];
            Arrays.fill(widths, 3);
            for (int r = 0; r < rows.size(); r++) {
                String[] row = rows.get(r);
                if (row.length != widths.length) throw new IllegalStateException("Inconsistent table columns");
                for (int c = 0; c < widths.length; c++) {
                    if (r == 1 && !row[c].matches("-{3,}")) throw new IllegalStateException("Invalid table separator");
                    widths[c] = Math.max(widths[c], row[c].length());
                }
            }
            for (int r = 0; r < rows.size(); r++) {
                StringJoiner joined = new StringJoiner(" | ", "| ", " |");
                for (int c = 0; c < widths.length; c++) {
                    String cell = r == 1 ? "-".repeat(widths[c]) : rows.get(r)[c];
                    joined.add(cell + " ".repeat(widths[c] - cell.length()));
                }
                formatted.add(joined.toString());
            }
            tables++;
        }
        String text = String.join("\n", formatted) + "\n";
        Matcher links = Pattern.compile("\\[[^\\]]+\\]\\(([^)]+)\\)").matcher(text);
        int files = 0;
        while (links.find()) {
            String target = links.group(1);
            if (target.startsWith("https://") || target.startsWith("#")) continue;
            target = target.split("#", 2)[0];
            if (!Files.isRegularFile(Path.of(target))) throw new IllegalStateException("Missing evidence: " + target);
            files++;
        }
        Set<String> references = new HashSet<>();
        Matcher definitions = Pattern.compile("(?m)^\\[([^\\]]+)\\]: https://\\S+$").matcher(text);
        while (definitions.find()) if (!references.add(definitions.group(1))) throw new IllegalStateException("Duplicate reference");
        Matcher used = Pattern.compile("\\[([A-Za-z][A-Za-z0-9 .-]*)\\](?![(:])").matcher(text);
        while (used.find()) if (!references.contains(used.group(1))) throw new IllegalStateException("Undefined reference: " + used.group(1));
        byte[] descriptor = Files.readAllBytes(Path.of("target/xbox-hci-pairing-report-map.bin"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor));
        if (descriptor.length != 283 || !text.contains(hash)) throw new IllegalStateException("Incorrect descriptor summary");
        String sweep = Files.readString(Path.of("target/led-sweep.txt"));
        if (sweep.lines().filter(line -> line.startsWith("Candidate ")).count() != 24 || sweep.lines().filter(line -> line.startsWith("Rejected: ")).count() != 24)
            throw new IllegalStateException("Incorrect sweep summary");
        Files.writeString(path, text);
        System.out.printf("Verified %d aligned tables, %d local file links, %d source references, descriptor hash, and 24 rejected LED candidates.%n", tables, files, references.size());
    }
}
