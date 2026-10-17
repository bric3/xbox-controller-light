//SOURCES ../XboxGattProbe.java
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import static java.lang.foreign.ValueLayout.*;

class ReadPdf extends XboxGattProbe {
    public static void main(String[] args) throws Throwable {
        if (args.length != 1) throw new IllegalArgumentException("Expected a PDF path");
        SymbolLookup.libraryLookup("/System/Library/Frameworks/PDFKit.framework/PDFKit", arena);
        MemorySegment pool = message(type("NSAutoreleasePool"), "new");
        MemorySegment url = message(type("NSURL"), "fileURLWithPath:", stringObject(args[0]));
        MemorySegment document = message(message(type("PDFDocument"), "alloc"), "initWithURL:", url);
        if (document.address() == 0) throw new IllegalArgumentException("Could not open PDF");
        MemorySegment content = message(document, "string");
        if (content.address() == 0) throw new IllegalArgumentException("PDF has no extractable text");
        MemorySegment data = (MemorySegment) nativeCall("objc_msgSend",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG),
                content, selector("dataUsingEncoding:"), 4L);
        System.out.println(new String(message(data, "bytes").reinterpret(number(data, "length")).toArray(JAVA_BYTE), StandardCharsets.UTF_8));
        message(document, "release");
        message(pool, "drain");
    }
}
