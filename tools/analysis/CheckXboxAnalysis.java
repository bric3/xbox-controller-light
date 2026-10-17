// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

class CheckXboxAnalysis {
    static String slug(String heading) {
        return heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _-]", "").replace(' ', '-');
    }

    static String prose(String text) {
        var result = new StringBuilder();
        boolean fenced = false;
        for (String line : text.lines().toList()) {
            if (line.startsWith("```")) fenced = !fenced;
            else if (!fenced) result.append(line).append('\n');
        }
        if (fenced) throw new IllegalStateException("Unclosed code fence");
        return result.toString();
    }

    static Set<String> anchors(String text) {
        var result = new HashSet<String>();
        var counts = new HashMap<String, Integer>();
        for (String line : prose(text).lines().toList()) {
            if (!line.matches("^#{1,6} .+")) continue;
            String base = slug(line.replaceFirst("^#+ ", ""));
            int count = counts.merge(base, 1, Integer::sum);
            result.add(count == 1 ? base : base + "-" + (count - 1));
        }
        return result;
    }

    static String formatTables(String text) {
        List<String> lines = text.lines().toList();
        var formatted = new ArrayList<String>();
        boolean fenced = false;
        for (int i = 0; i < lines.size();) {
            String line = lines.get(i);
            if (line.startsWith("```")) fenced = !fenced;
            if (fenced || !line.startsWith("|")) {
                formatted.add(line);
                i++;
                continue;
            }
            var rows = new ArrayList<String[]>();
            while (i < lines.size() && lines.get(i).startsWith("|")) {
                String[] cells = lines.get(i++).split("(?<!\\\\)\\|", -1);
                if (!cells[0].isBlank() || !cells[cells.length - 1].isBlank())
                    throw new IllegalStateException("Invalid table boundary");
                rows.add(Arrays.stream(cells, 1, cells.length - 1).map(String::strip).toArray(String[]::new));
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
                var joined = new StringJoiner(" | ", "| ", " |");
                for (int c = 0; c < widths.length; c++) {
                    String cell = r == 1 ? "-".repeat(widths[c]) : rows.get(r)[c];
                    joined.add(cell + " ".repeat(widths[c] - cell.length()));
                }
                formatted.add(joined.toString());
            }
        }
        return String.join("\n", formatted) + "\n";
    }

    static void checkLink(Path document, String target) throws Exception {
        if (target.startsWith("https://") || target.startsWith("http://")) return;
        String[] parts = target.split("#", 2);
        Path file = parts[0].isEmpty() ? document : document.resolveSibling(parts[0]).normalize();
        if (file.startsWith("target")) throw new IllegalStateException("Ignored evidence link: " + target);
        if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing link: " + document + " -> " + target);
        if (parts.length == 2 && !anchors(Files.readString(file)).contains(parts[1]))
            throw new IllegalStateException("Missing anchor: " + document + " -> " + target);
    }

    static void selfTest() throws Exception {
        String sample = "| A | B |\n| --- | --- |\n| Xbox X\\|S | 1 |\n";
        String formatted = formatTables(sample);
        if (!formatTables(formatted).equals(formatted) || !formatted.contains("Xbox X\\|S"))
            throw new AssertionError("Table formatting must preserve escaped pipes and be idempotent");
        if (!anchors("# Same\n# Same\n```text\n# Hidden\n```\n").equals(Set.of("same", "same-1")))
            throw new AssertionError("Anchors must ignore fenced code and number duplicate headings");
        try {
            checkLink(Path.of("README.md"), "target/missing.tsv");
            throw new AssertionError("Ignored links must fail");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Ignored evidence")) throw expected;
        }
    }

    public static void main(String[] args) throws Exception {
        selfTest();
        boolean format = args.length == 1 && args[0].equals("--format");
        if (args.length != 0 && !format) throw new IllegalArgumentException("Usage: CheckXboxAnalysis.java [--format]");
        var documents = new ArrayList<>(List.of(Path.of("README.md"), Path.of("AGENTS.md")));
        try (var files = Files.list(Path.of("docs"))) {
            documents.addAll(files.filter(p -> p.toString().endsWith(".md")).sorted().toList());
        }
        int links = 0;
        for (Path document : documents) {
            String text = Files.readString(document);
            String formatted = formatTables(text);
            if (format) Files.writeString(document, formatted);
            else if (!text.equals(formatted)) throw new IllegalStateException("Unaligned table: " + document);
            String body = prose(formatted);
            Map<String, String> definitions = new HashMap<>();
            var reference = Pattern.compile("(?m)^\\[([^\\]]+)\\]: (\\S+)$").matcher(body);
            while (reference.find()) {
                if (definitions.put(reference.group(1), reference.group(2)) != null)
                    throw new IllegalStateException("Duplicate reference: " + reference.group(1));
                checkLink(document, reference.group(2));
            }
            var inline = Pattern.compile("\\[[^\\]]+\\]\\(([^)]+)\\)").matcher(body);
            while (inline.find()) { checkLink(document, inline.group(1)); links++; }
            var shorthand = Pattern.compile("(?<!`)\\[([A-Za-z][A-Za-z0-9 .-]+)\\](?![(:])").matcher(body);
            while (shorthand.find()) {
                if (!definitions.containsKey(shorthand.group(1)))
                    throw new IllegalStateException("Undefined reference: " + document + ": " + shorthand.group(1));
            }
            long opens = body.lines().filter(line -> line.equals("<details>")).count();
            long closes = body.lines().filter(line -> line.equals("</details>")).count();
            if (opens != closes) throw new IllegalStateException("Unclosed details: " + document);
        }
        System.out.printf("Verified %d Markdown files: aligned tables, %d links, anchors, references, fences and details.%n", documents.size(), links);
    }
}
