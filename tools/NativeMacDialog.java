//SOURCES XboxGattProbe.java
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;

/** Native, untimed prompts for physical experiment steps. */
class NativeMacDialog extends XboxGattProbe {
    static MemorySegment application = MemorySegment.NULL;
    static MemorySegment progressAlert = MemorySegment.NULL;

    static void initialise() throws Throwable {
        if (application.address() != 0) return;
        SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena);
        application = message(type("NSApplication"), "sharedApplication");
        nativeCall("objc_msgSend", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG),
                application, selector("setActivationPolicy:"), 1L);
        message(application, "finishLaunching");
    }

    static void pumpEvents() throws Throwable {
        if (application.address() == 0) return;
        MemorySegment pool = message(type("NSAutoreleasePool"), "new");
        try {
            for (int i = 0; i < 32; i++) {
                MemorySegment event = (MemorySegment) nativeCall("objc_msgSend",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE),
                        application, selector("nextEventMatchingMask:untilDate:inMode:dequeue:"), -1L,
                        message(type("NSDate"), "distantPast"), stringObject("kCFRunLoopDefaultMode"), (byte)1);
                if (event.address() == 0) break;
                message(application, "sendEvent:", event);
            }
            message(application, "updateWindows");
        } finally { message(pool, "drain"); }
    }

    static void pause(long milliseconds) throws Throwable {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);
        do {
            pumpEvents();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            Thread.sleep(Math.min(20, Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
        } while (true);
    }

    static void progress(String title, String detail) throws Throwable {
        initialise();
        MemorySegment pool = message(type("NSAutoreleasePool"), "new");
        try {
            if (progressAlert.address() == 0) progressAlert = message(type("NSAlert"), "new");
            message(progressAlert, "setMessageText:", stringObject(title));
            message(progressAlert, "setInformativeText:", stringObject(detail));
            message(progressAlert, "layout");
            for (MemorySegment button : elements(message(progressAlert, "buttons"))) {
                nativeCall("objc_msgSend", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE),
                        button, selector("setHidden:"), (byte)1);
            }
            MemorySegment window = message(progressAlert, "window");
            message(window, "center");
            message(window, "orderFrontRegardless");
            pumpEvents();
        } finally { message(pool, "drain"); }
    }

    static void hideProgress() throws Throwable {
        if (progressAlert.address() != 0) message(message(progressAlert, "window"), "orderOut:", MemorySegment.NULL);
        pumpEvents();
    }

    static int choose(String title, String detail, String... buttons) throws Throwable {
        initialise();
        hideProgress();
        MemorySegment pool = message(type("NSAutoreleasePool"), "new");
        nativeCall("objc_msgSend", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE),
                application, selector("activateIgnoringOtherApps:"), (byte) 1);
        MemorySegment alert = message(type("NSAlert"), "new");
        try {
            message(alert, "setMessageText:", stringObject(title));
            message(alert, "setInformativeText:", stringObject(detail));
            for (String button : buttons) message(alert, "addButtonWithTitle:", stringObject(button));
            return Math.toIntExact(number(alert, "runModal") - 1000);
        } finally {
            message(message(alert, "window"), "orderOut:", MemorySegment.NULL);
            message(alert, "release");
            pumpEvents();
            message(pool, "drain");
        }
    }

    static void proceed(String title, String detail) throws Throwable {
        if (choose(title, detail, "OK", "Cancel") != 0)
            throw new java.util.concurrent.CancellationException("Cancelled: " + title);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--self-test")) {
            progress("Dialog self-test", "Checking responsive progress updates...");
            MemorySegment window = message(progressAlert, "window");
            if (number(window, "isVisible") == 0) throw new AssertionError("Progress window is hidden");
            pause(500);
            progress("Dialog self-test", "Progress updated. This window closes automatically.");
            if (!string(message(progressAlert, "informativeText")).startsWith("Progress updated."))
                throw new AssertionError("Progress text did not update");
            pause(500);
            hideProgress();
            if (number(window, "isVisible") != 0) throw new AssertionError("Progress window did not close");
            System.out.println("NativeMacDialog self-test passed");
            return;
        }
        if (args.length < 2) throw new IllegalArgumentException("Usage: NativeMacDialog.java TITLE MESSAGE [BUTTON...]");
        String[] buttons = args.length == 2 ? new String[]{"OK", "Cancel"}
                : java.util.Arrays.copyOfRange(args, 2, args.length);
        int result = choose(args[0], args[1], buttons);
        System.out.println("Dialog response: " + (result >= 0 && result < buttons.length ? buttons[result] : "dismissed"));
        if (result < 0 || result >= buttons.length || buttons[result].equals("Cancel")) System.exit(1);
    }
}
