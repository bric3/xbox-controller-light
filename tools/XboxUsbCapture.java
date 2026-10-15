//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import java.util.Arrays;
import java.util.HexFormat;

import static java.lang.foreign.ValueLayout.*;

class XboxUsbCapture {
    static final Linker linker = Linker.nativeLinker();
    static final Arena arena = Arena.ofConfined();
    static final SymbolLookup symbols = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena)
            .or(SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", arena));

    static Object call(String name, FunctionDescriptor signature, Object... args) throws Throwable {
        return linker.downcallHandle(symbols.find(name).orElseThrow(), signature).invokeWithArguments(args);
    }

    static MemorySegment selector(String name) throws Throwable {
        return (MemorySegment) call("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(name));
    }

    static MemorySegment type(String name) throws Throwable {
        var type = (MemorySegment) call("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(name));
        if (type.address() == 0) throw new IllegalStateException("Unavailable Objective-C class: " + name);
        return type;
    }

    static MemorySegment object(MemorySegment receiver, String name, MemorySegment... args) throws Throwable {
        MemoryLayout[] layouts = new MemoryLayout[args.length + 2];
        Arrays.fill(layouts, ADDRESS);
        Object[] values = new Object[args.length + 2];
        values[0] = receiver;
        values[1] = selector(name);
        System.arraycopy(args, 0, values, 2, args.length);
        return (MemorySegment) call("objc_msgSend", FunctionDescriptor.of(ADDRESS, layouts), values);
    }

    static void release(MemorySegment value) throws Throwable {
        if (value.address() != 0)
            call("objc_msgSend", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), value, selector("release"));
    }

    static MemorySegment number(int value) throws Throwable {
        return (MemorySegment) call("objc_msgSend", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                type("NSNumber"), selector("numberWithInt:"), value);
    }

    static void check(int status, String operation) {
        if (status != 0) throw new IllegalStateException("%s: IOReturn 0x%08x".formatted(operation, status));
    }

    static IllegalStateException failure(String operation, MemorySegment errorOut) throws Throwable {
        var error = errorOut.get(ADDRESS, 0);
        String detail = error.address() == 0 ? "no NSError" :
                object(object(error, "description"), "UTF8String").reinterpret(8192).getString(0);
        return new IllegalStateException(operation + ": " + detail);
    }

    static int findDevice() throws Throwable {
        var dictionary = object(type("IOUSBHostDevice"),
                "createMatchingDictionaryWithVendorID:productID:bcdDevice:deviceClass:deviceSubclass:deviceProtocol:speed:productIDArray:",
                number(0x045e), number(0x0b12), MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        var iteratorOut = arena.allocate(JAVA_INT);
        check((int) call("IOServiceGetMatchingServices", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
                0, dictionary, iteratorOut), "Find USB controller");
        int iterator = iteratorOut.get(JAVA_INT, 0);
        int selected = 0;
        int matches = 0;
        try {
            int service;
            while ((service = (int) call("IOIteratorNext", FunctionDescriptor.of(JAVA_INT, JAVA_INT), iterator)) != 0) {
                matches++;
                if (selected == 0) selected = service;
                else call("IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT), service);
            }
            if (matches != 1) throw new IllegalStateException("Expected one USB 045e:0b12 controller, found " + matches);
            int result = selected;
            selected = 0;
            return result;
        } finally {
            if (selected != 0) call("IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT), selected);
            call("IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT), iterator);
        }
    }

    static MemorySegment open(String className, int service, long options) throws Throwable {
        var errorOut = arena.allocate(ADDRESS);
        var result = (MemorySegment) call("objc_msgSend",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS),
                object(type(className), "alloc"), selector("initWithIOService:options:queue:error:interestHandler:"),
                service, options, MemorySegment.NULL, errorOut, MemorySegment.NULL);
        if (result.address() == 0) throw failure("Open " + className, errorOut);
        return result;
    }

    static void destroy(MemorySegment object) throws Throwable {
        try {
            call("objc_msgSend", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), object, selector("destroy"));
        } finally {
            release(object);
        }
    }

    static int word(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset]) | Byte.toUnsignedInt(data[offset + 1]) << 8;
    }

    static void checkDescriptor(byte[] data) {
        if (data.length != 18 || data[0] != 18 || data[1] != 1 || word(data, 8) != 0x045e || word(data, 10) != 0x0b12)
            throw new IllegalArgumentException("Unexpected USB device descriptor");
    }

    static void capture(int service) throws Throwable {
        var device = open("IOUSBHostDevice", service, 1); // DeviceCapture; destroy restores driver matching.
        System.out.println("Captured USB 045e:0b12 with IOUSBHostDevice.");
        try {
            var descriptor = object(device, "deviceDescriptor");
            if (descriptor.address() == 0) throw new IllegalStateException("Missing USB device descriptor");
            byte[] bytes = descriptor.reinterpret(18).toArray(JAVA_BYTE);
            checkDescriptor(bytes);
            System.out.println("Device descriptor: " + HexFormat.ofDelimiter(" ").formatHex(bytes));
        } finally {
            // Apple's capture API resets the USB connection here and re-registers its drivers.
            destroy(device);
            System.out.println("Capture released; USB reset and driver matching requested.");
        }
    }

    static void selfTest() {
        byte[] descriptor = HexFormat.of().parseHex("12010002ff47d0405e04120b090501020301");
        checkDescriptor(descriptor);
        reject(new byte[0]);
        reject(Arrays.copyOf(descriptor, 17));
        for (int offset : new int[]{0, 1, 8, 10}) {
            byte[] invalid = descriptor.clone();
            invalid[offset] ^= 1;
            reject(invalid);
        }
        System.out.println("Self-test passed: accepted target descriptor and rejected six invalid descriptors.");
    }

    static void reject(byte[] descriptor) {
        try {
            checkDescriptor(descriptor);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted an invalid descriptor");
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 1 || !Arrays.asList("--inspect", "--capture", "--self-test").contains(args[0]))
            throw new IllegalArgumentException("Usage: XboxUsbCapture.java --inspect | --capture | --self-test");
        if (args[0].equals("--self-test")) {
            selfTest();
            arena.close();
            return;
        }

        SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena);
        SymbolLookup.libraryLookup("/System/Library/Frameworks/IOUSBHost.framework/IOUSBHost", arena);
        var pool = object(type("NSAutoreleasePool"), "new");
        try {
            int service = findDevice();
            try {
                System.out.println("Found exactly one USB 045e:0b12 controller.");
                if (args[0].equals("--capture")) {
                    int uid = (int) linker.downcallHandle(linker.defaultLookup().find("geteuid").orElseThrow(),
                            FunctionDescriptor.of(JAVA_INT)).invokeExact();
                    if (uid != 0) throw new IllegalStateException("USB capture requires sudo; no capture attempted.");
                    capture(service);
                }
            } finally {
                call("IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT), service);
            }
        } finally {
            release(pool);
            arena.close();
        }
    }
}
