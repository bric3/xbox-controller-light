//SOURCES XboxBrightnessCampaign.java
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Controlled record-0 cap experiment with a saved original and mandatory restore phase. */
class XboxStoredCapExperiment {
    static final int TRIAL_CAP = 5;
    static byte[] controllerSerial;
    static boolean changed;
    static Path recovery;

    record Plan(byte[] original, byte[] trial) {
        Plan(byte[] bytes) throws IOException {
            this(bytes.clone(), dimmed(bytes));
        }

        static byte[] dimmed(byte[] bytes) throws IOException {
            if (XboxBrightnessCampaign.recordCap(bytes) != 80 || bytes[1] != 26)
                throw new IOException("Expected the previously observed 26-byte record with candidate cap 80");
            byte[] trial = bytes.clone();
            trial[24] = TRIAL_CAP;
            int sum = 0;
            for (int i = 2; i < 28; i++) sum = (sum + Byte.toUnsignedInt(trial[i])) & 255;
            trial[28] = (byte)sum;
            XboxBrightnessCampaign.recordCap(trial);
            return trial;
        }

        byte[] writePayload(byte[] slot) {
            if (!Arrays.equals(slot, original) && !Arrays.equals(slot, trial))
                throw new IllegalArgumentException("Only the saved original and one-byte cap trial are writable");
            // The driver sends record ID, length and payload; the controller supplies its checksum.
            return Arrays.copyOf(slot, 28);
        }

        void requireKnown(byte[] slot) throws IOException {
            if (!Arrays.equals(slot, original) && !Arrays.equals(slot, trial))
                throw new IOException("Record differs from both snapshots; stop before writing identity data");
        }
    }

    static void record(String text) throws IOException { XboxBrightnessCampaign.record(text); }

    static XboxBrightnessCampaign.Usb connectUsb(String detail) throws Throwable {
        NativeMacDialog.proceed("Connect the controller by USB", detail
                + "\n\n1. Connect the Xbox controller to the Mac by USB.\n\n2. Wait for the controller to start.\n\n3. Click OK.");
        var usb = new XboxBrightnessCampaign.Usb();
        try {
            NativeMacDialog.progress("Opening the factory interface", "Sending the three volatile unlock keys...");
            for (int i = 0; i < 3; i++) {
                XboxBrightnessCampaign.factoryAttempted = true;
                usb.send(XboxBrightnessCampaign.unlock(i)); NativeMacDialog.pause(100);
            }
            usb.reply(0x211);
            usb.send(new XboxBrightnessCampaign.Command(3, 0x308, new byte[2]));
            XboxBrightnessCampaign.recordLayout(usb.reply(0x308));
            return usb;
        } catch (Throwable failure) { usb.close(); throw failure; }
    }

    static byte[] read(XboxBrightnessCampaign.Usb usb) throws Throwable {
        usb.send(new XboxBrightnessCampaign.Command(3, 0x300, new byte[]{0x60, 0, 32}));
        byte[] first = usb.reply(0x300);
        XboxBrightnessCampaign.recordCap(first);
        usb.send(new XboxBrightnessCampaign.Command(3, 0x300, new byte[]{0x60, 0, 32}));
        if (!Arrays.equals(first, usb.reply(0x300))) throw new IOException("Repeated record-0 reads differ");
        return first;
    }

    static void write(XboxBrightnessCampaign.Usb usb, Plan plan, byte[] slot) throws Throwable {
        byte[] payload = plan.writePayload(slot);
        record("RECORD_WRITE intended_cap=" + Byte.toUnsignedInt(slot[24]) + " record=0 length=26");
        usb.transfer(0x40, 0x10, 0x030e, 0x0306, payload);
        usb.reply(0x306);
        if (!Arrays.equals(read(usb), slot)) throw new IOException("Record write did not match the expected full slot");
        record("RECORD_WRITE_VERIFIED cap=" + Byte.toUnsignedInt(slot[24]));
    }

    static void baselineWriter(XboxBrightnessCampaign.Usb usb, Plan plan) throws Throwable {
        changed = true;
        try { write(usb, plan, plan.original); changed = false; }
        catch (Throwable failure) {
            byte[] actual = read(usb);
            if (!Arrays.equals(actual, plan.original))
                throw new IOException("Baseline write failed and original record is not intact; recovery snapshot: " + recovery, failure);
            changed = false;
            record("BASELINE_WRITER_UNAVAILABLE original record remains byte-for-byte intact");
            throw new IOException("The controller did not complete the unchanged-record write. Cap 80 remains intact; the dimming trial was not started.", failure);
        }
    }

    static int bluetoothObservation(String port, boolean baseline) throws Throwable {
        try (var serial = new XboxBleExperiment.Serial(port)) {
            if (!serial.last("PING", "PONG ").equals("PONG XboxBleProbe 1")) throw new IOException("Unexpected XIAO probe");
            try {
                XboxBleExperiment.run(serial, "--inspect");
                if (controllerSerial == null) controllerSerial = XboxBleExperiment.expectedSerial.clone();
                else if (!Arrays.equals(controllerSerial, XboxBleExperiment.expectedSerial)) throw new IOException("Different BLE controller after reboot");
                while (true) {
                    XboxBleExperiment.ensureConnected(serial);
                    NativeMacDialog.progress("Observe steady Bluetooth brightness", "Wait five seconds for the connection light to settle.\n\nPairing and connection flashes do not count.");
                    NativeMacDialog.pause(5000);
                    int answer = baseline
                            ? NativeMacDialog.choose("Remember the original brightness", "This is the steady Bluetooth light with original cap 80.\n\nObserve it for comparison, then click OK.", "OK", "Cancel")
                            : NativeMacDialog.choose("Did the cap lower Bluetooth brightness?", "Compare this steady light with the original cap-80 baseline.\n\nThe stored candidate is now 5. The original will be restored next.", "Dimmer", "Unchanged", "Didn't see", "Cancel");
                    if (baseline && answer != 0 || !baseline && (answer < 0 || answer > 2))
                        throw new CancellationException("Bluetooth observation cancelled");
                    if (!serial.last("STATUS", "STATUS ").startsWith("STATUS 1 1 ")) {
                        record("OBSERVATION_DISCARDED controller disconnected before confirmation");
                        continue;
                    }
                    if (!baseline && answer == 2) continue;
                    record("BLUETOOTH_OBSERVATION " + (baseline ? "original baseline observed" : answer == 0 ? "dimmer" : "unchanged"));
                    return answer;
                }
            } finally {
                try {
                    if (serial.last("STATUS", "STATUS ").startsWith("STATUS 1 ")) serial.request("DISCONNECT", "DISCONNECTED ");
                } catch (Throwable cleanup) { record("BLE_CLEANUP " + cleanup); }
            }
        }
    }

    static void restore(Plan plan) throws Throwable {
        try (var usb = connectUsb("Restore the original record. The saved snapshot is:\n" + recovery)) {
            byte[] actual = read(usb);
            plan.requireKnown(actual);
            if (!Arrays.equals(actual, plan.original)) write(usb, plan, plan.original);
            changed = false;
            record("RESTORE_VERIFIED original 32-byte slot matches twice");
        }
    }

    static void run(String port) throws Throwable {
        Plan plan;
        try (var usb = connectUsb("Prepare a controlled cap test. The runner reads and saves the original before any EEPROM write.")) {
            plan = new Plan(read(usb));
            recovery = XboxBrightnessCampaign.savePrivate("cap-original.bin", plan.original).toAbsolutePath();
            XboxBrightnessCampaign.savePrivate("cap-trial.bin", plan.trial);
            String quoted = "'" + recovery.toString().replace("'", "'\\''") + "'";
            XboxBrightnessCampaign.savePrivate("restore-command.txt", ("mise exec java@corretto-25 -- jbang tools/XboxStoredCapExperiment.java --restore "
                    + quoted + "\n").getBytes(StandardCharsets.UTF_8));
            NativeMacDialog.proceed("Review the stored-cap experiment",
                    "This test writes EEPROM record 0, which also contains controller identity data.\n\n"
                    + "Candidate cap: 80 -> 5 -> 80. All identity bytes are preserved. The checksum is regenerated by the controller.\n\n"
                    + "1. First, write the original bytes unchanged and verify them.\n\n2. Observe the original Bluetooth light.\n\n"
                    + "3. Write cap 5, reboot and observe Bluetooth brightness.\n\n4. Restore and verify the original record.\n\n"
                    + "If restoration cannot finish, cap 5 may remain stored. Recovery file:\n" + recovery + "\n\nClick OK to run this EEPROM write/restore test, or Cancel to keep the test read-only.");
            baselineWriter(usb, plan);
        }
        bluetoothObservation(port, true);
        try {
            try (var usb = connectUsb("The baseline is recorded. Next, change only candidate cap 80 to 5.")) {
                if (!Arrays.equals(read(usb), plan.original)) throw new IOException("Original record changed since backup; stopping");
                NativeMacDialog.proceed("Apply candidate cap 5", "Click OK to write cap 5 and verify the whole record. The following dialogs guide a reboot, the Bluetooth observation and restoration.");
                changed = true;
                write(usb, plan, plan.trial);
            }
            NativeMacDialog.proceed("Reboot before the Bluetooth observation", "1. Unplug the controller's USB cable.\n\n2. Hold the Xbox button until the controller is off.\n\n3. Click OK, then follow the XIAO pairing dialog.");
            bluetoothObservation(port, false);
        } finally {
            if (changed) restore(plan);
        }
    }

    static void selfTest() throws Exception {
        byte[] original = new byte[32]; original[1] = 26;
        for (int i = 2; i < 28; i++) original[i] = (byte)(i * 13);
        original[24] = 80;
        int checksum = 0;
        for (int i = 2; i < 28; i++) checksum = (checksum + Byte.toUnsignedInt(original[i])) & 255;
        original[28] = (byte)checksum;
        Plan plan = new Plan(original);
        if (plan.trial[24] != 5 || XboxBrightnessCampaign.recordCap(plan.trial) != 5 || plan.writePayload(plan.original).length != 28
                || Byte.toUnsignedInt(plan.trial[28]) != (checksum - 75 & 255)
                || !Arrays.equals(plan.writePayload(plan.original), Arrays.copyOf(original, 28)))
            throw new AssertionError("Incorrect cap plan");
        for (int i = 0; i < 32; i++) if (i != 24 && i != 28 && plan.original[i] != plan.trial[i])
            throw new AssertionError("Plan changed an identity or padding byte");
        byte[] other = plan.trial.clone(); other[2] = 1;
        try { plan.writePayload(other); throw new AssertionError("Accepted unrelated record change"); } catch (IllegalArgumentException expected) { }
        try { plan.requireKnown(other); throw new AssertionError("Accepted unknown controller record"); } catch (IOException expected) { }
        original[24] = 79; original[28]--;
        try { new Plan(original); throw new AssertionError("Accepted a different original cap"); } catch (IOException expected) { }
        System.out.println("Stored-cap self-test passed: one cap byte plus checksum, exact restore payload, identity and original-cap guards.");
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length != 2 || !args[0].equals("--run") && !args[0].equals("--restore"))
            throw new IllegalArgumentException("Usage: XboxStoredCapExperiment.java --self-test | --run PORT | --restore ORIGINAL_SLOT");
        XboxBrightnessCampaign.directory = Path.of("target/campaigns", Instant.now().toString().replace(':', '-'));
        Files.createDirectories(XboxBrightnessCampaign.directory);
        Path log = XboxBrightnessCampaign.directory.resolve("stored-cap.log");
        Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = new PrintWriter(Files.newBufferedWriter(log))) {
            XboxBrightnessCampaign.transcript = writer;
            record("STORED_CAP_EXPERIMENT " + log.toAbsolutePath());
            try {
                if (args[0].equals("--restore")) {
                    recovery = Path.of(args[1]).toAbsolutePath();
                    changed = true;
                    restore(new Plan(Files.readAllBytes(recovery)));
                } else run(args[1]);
                record("RESULT Stored-cap experiment completed; original record restored");
            } catch (Throwable failure) {
                record("STOPPED " + failure);
                NativeMacDialog.choose("Stored-cap test stopped", failure.getMessage()
                        + (changed ? "\n\nRestoration is pending. Keep the original snapshot:\n" + recovery : "\n\nNo changed cap remains according to the completed checks."), "OK");
                if (!(failure instanceof CancellationException)) throw failure;
            } finally { NativeMacDialog.hideProgress(); XboxBrightnessCampaign.powerCycle(); }
        }
    }
}
