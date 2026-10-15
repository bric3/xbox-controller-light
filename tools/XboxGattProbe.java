//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

class XboxGattProbe {
    static final Arena arena = Arena.ofShared();
    static final Linker linker = Linker.nativeLinker();
    static final SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
    static MemorySegment delegate, central, peripheral = MemorySegment.NULL;
    static boolean done, started, failed;
    static boolean hidOnly;
    static int pending;
    static final Set<Long> discoveredServices = new HashSet<>();
    static Object nativeCall(String name, FunctionDescriptor descriptor, Object... args) throws Throwable {
        return linker.downcallHandle(objc.find(name).orElseThrow(), descriptor).invokeWithArguments(args);
    }
    static MemorySegment selector(String name) throws Throwable {
        return (MemorySegment) nativeCall("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(name));
    }
    static MemorySegment type(String name) throws Throwable {
        return (MemorySegment) nativeCall("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(name));
    }
    static MemorySegment message(MemorySegment object, String name, MemorySegment... args) throws Throwable {
        MemoryLayout[] layouts = new MemoryLayout[args.length + 2];
        Arrays.fill(layouts, ADDRESS);
        Object[] values = new Object[args.length + 2];
        values[0] = object;
        values[1] = selector(name);
        System.arraycopy(args, 0, values, 2, args.length);
        return (MemorySegment) nativeCall("objc_msgSend", FunctionDescriptor.of(ADDRESS, layouts), values);
    }
    static long number(MemorySegment object, String name) throws Throwable {
        return (long) nativeCall("objc_msgSend", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS), object, selector(name));
    }
    static MemorySegment at(MemorySegment array, long index) throws Throwable {
        return (MemorySegment) nativeCall("objc_msgSend", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG), array, selector("objectAtIndex:"), index);
    }
    static MemorySegment stringObject(String value) throws Throwable {
        return message(type("NSString"), "stringWithUTF8String:", arena.allocateFrom(value));
    }
    static String string(MemorySegment value) throws Throwable {
        return value.address() == 0 ? "nil" : message(value, "UTF8String").reinterpret(4096).getString(0);
    }
    static String uuid(MemorySegment object) throws Throwable {
        return string(message(message(object, "UUID"), "UUIDString"));
    }
    static List<MemorySegment> elements(MemorySegment array) throws Throwable {
        List<MemorySegment> result = new ArrayList<>();
        for (long i = 0, size = number(array, "count"); i < size; i++) result.add(at(array, i));
        return result;
    }
    static String error(MemorySegment value) throws Throwable {
        return value.address() == 0 ? "none" : string(message(value, "localizedDescription"));
    }
    static void reportFailure(Throwable error) {
        error.printStackTrace();
        failed = done = true;
    }
    static void register(MemorySegment clazz, String selectorName, String methodName, int count) throws Throwable {
        Class<?>[] parameters = new Class<?>[count];
        Arrays.fill(parameters, MemorySegment.class);
        MemoryLayout[] layouts = new MemoryLayout[count];
        Arrays.fill(layouts, ADDRESS);
        MethodHandle method = MethodHandles.lookup().findStatic(XboxGattProbe.class, methodName, MethodType.methodType(void.class, parameters));
        MemorySegment callback = linker.upcallStub(method, FunctionDescriptor.ofVoid(layouts), arena);
        byte added = (byte) nativeCall("class_addMethod", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                clazz, selector(selectorName), callback, arena.allocateFrom("v@:" + "@".repeat(count - 2)));
        if (added == 0) throw new IllegalStateException("Cannot register " + selectorName);
    }
    static void selectPeripheral(MemorySegment candidate) throws Throwable {
        if (peripheral.address() != 0) return;
        String name = string(message(candidate, "name"));
        if (!name.toLowerCase(Locale.ROOT).contains("xbox")) return;
        peripheral = message(candidate, "retain");
        System.out.println("Selected peripheral: " + name);
        message(central, "stopScan");
        message(peripheral, "setDelegate:", delegate);
        message(central, "connectPeripheral:options:", peripheral, MemorySegment.NULL);
    }
    static void state(MemorySegment self, MemorySegment cmd, MemorySegment manager) {
        try {
            long state = number(manager, "state");
            System.out.println("CoreBluetooth state: " + state + " (5=powered on, 3=unauthorized)");
            if (state == 3 || state == 2 || state == 4) { failed = done = true; return; }
            if (state != 5 || started) return;
            started = true;
            MemorySegment services = message(type("NSMutableArray"), "array");
            for (String id : List.of("1800", "1801", "180A", "180F", "1812"))
                message(services, "addObject:", message(type("CBUUID"), "UUIDWithString:", stringObject(id)));
            MemorySegment connected = message(manager, "retrieveConnectedPeripheralsWithServices:", services);
            System.out.println("Connected peripherals with standard HID/device/battery services: " + number(connected, "count"));
            for (MemorySegment candidate : elements(connected)) selectPeripheral(candidate);
            if (peripheral.address() == 0) {
                System.out.println("No connected Xbox peripheral returned; scanning advertisements for 15 seconds");
                message(manager, "scanForPeripheralsWithServices:options:", MemorySegment.NULL, MemorySegment.NULL);
            }
        } catch (Throwable error) { reportFailure(error); }
    }
    static void discovered(MemorySegment self, MemorySegment cmd, MemorySegment manager, MemorySegment candidate, MemorySegment advertisement, MemorySegment rssi) {
        try { selectPeripheral(candidate); } catch (Throwable error) { reportFailure(error); }
    }
    static void connected(MemorySegment self, MemorySegment cmd, MemorySegment manager, MemorySegment device) {
        try {
            System.out.println("Connected to Xbox peripheral; discovering all exposed GATT services");
            pending++;
            MemorySegment requested = MemorySegment.NULL;
            if (hidOnly) {
                requested = message(type("NSArray"), "arrayWithObject:", message(type("CBUUID"), "UUIDWithString:", stringObject("1812")));
                System.out.println("Explicitly requesting HID service 1812");
            }
            message(device, "discoverServices:", requested);
        } catch (Throwable error) { reportFailure(error); }
    }
    static void connectFailed(MemorySegment self, MemorySegment cmd, MemorySegment manager, MemorySegment device, MemorySegment err) {
        try { System.out.println("Connection failed: " + error(err)); failed = done = true; }
        catch (Throwable error) { reportFailure(error); }
    }
    static void inspectService(MemorySegment device, MemorySegment service) throws Throwable {
        if (!discoveredServices.add(service.address())) return;
        System.out.println("Service: " + uuid(service));
        pending += 2;
        message(device, "discoverCharacteristics:forService:", MemorySegment.NULL, service);
        message(device, "discoverIncludedServices:forService:", MemorySegment.NULL, service);
    }
    static void services(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment err) {
        try {
            System.out.println("Service discovery error: " + error(err));
            if (err.address() != 0) failed = true;
            List<MemorySegment> services = elements(message(device, "services"));
            System.out.println("Exposed GATT service count: " + services.size());
            for (MemorySegment service : services) inspectService(device, service);
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    static void includedServices(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment service, MemorySegment err) {
        try {
            List<MemorySegment> included = elements(message(service, "includedServices"));
            System.out.println("Included services: parent=" + uuid(service) + " count=" + included.size() + " error=" + error(err));
            if (err.address() != 0) failed = true;
            for (MemorySegment child : included) inspectService(device, child);
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    static void characteristics(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment service, MemorySegment err) {
        try {
            if (err.address() != 0) { System.out.println("Characteristic discovery error: " + error(err)); failed = true; }
            for (MemorySegment characteristic : elements(message(service, "characteristics"))) {
                long props = number(characteristic, "properties");
                System.out.printf("Characteristic: service=%s uuid=%s properties=0x%x read=%s write=%s notify=%s%n",
                        uuid(service), uuid(characteristic), props, (props & 2) != 0, (props & 12) != 0, (props & 48) != 0);
                pending++;
                message(device, "discoverDescriptorsForCharacteristic:", characteristic);
                if ((props & 2) != 0) {
                    pending++;
                    message(device, "readValueForCharacteristic:", characteristic);
                }
            }
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    static void descriptors(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment characteristic, MemorySegment err) {
        try {
            if (err.address() != 0) { System.out.println("Descriptor discovery error: " + error(err)); failed = true; }
            for (MemorySegment descriptor : elements(message(characteristic, "descriptors"))) {
                System.out.println("Descriptor: characteristic=" + uuid(characteristic) + " uuid=" + uuid(descriptor));
                pending++;
                message(device, "readValueForDescriptor:", descriptor);
            }
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    static void value(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment characteristic, MemorySegment err) {
        try {
            MemorySegment data = message(characteristic, "value");
            long length = number(data, "length");
            String hex = length == 0 ? "empty" : HexFormat.ofDelimiter(" ").formatHex(message(data, "bytes").reinterpret(length).toArray(JAVA_BYTE));
            System.out.println("Value: uuid=" + uuid(characteristic) + " bytes=" + length + " error=" + error(err) + " data=" + hex);
            if (err.address() != 0) failed = true;
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    static void descriptorValue(MemorySegment self, MemorySegment cmd, MemorySegment device, MemorySegment descriptor, MemorySegment err) {
        try {
            System.out.println("Descriptor value: uuid=" + uuid(descriptor) + " error=" + error(err) + " value=" + string(message(message(descriptor, "value"), "description")));
            if (err.address() != 0) failed = true;
            if (--pending == 0) done = true;
        } catch (Throwable error) { reportFailure(error); }
    }
    public static void main(String[] args) throws Throwable {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("hid")))
            throw new IllegalArgumentException("Usage: jbang tools/XboxGattProbe.java [hid]");
        hidOnly = args.length > 0 && args[0].equals("hid");
        SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreBluetooth.framework/CoreBluetooth", arena);
        SymbolLookup core = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
        MemorySegment pool = message(type("NSAutoreleasePool"), "new");
        MemorySegment clazz = (MemorySegment) nativeCall("objc_allocateClassPair", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG),
                type("NSObject"), arena.allocateFrom("XboxGattReadOnlyDelegate"), 0L);
        register(clazz, "centralManagerDidUpdateState:", "state", 3);
        register(clazz, "centralManager:didDiscoverPeripheral:advertisementData:RSSI:", "discovered", 6);
        register(clazz, "centralManager:didConnectPeripheral:", "connected", 4);
        register(clazz, "centralManager:didFailToConnectPeripheral:error:", "connectFailed", 5);
        register(clazz, "peripheral:didDiscoverServices:", "services", 4);
        register(clazz, "peripheral:didDiscoverIncludedServicesForService:error:", "includedServices", 5);
        register(clazz, "peripheral:didDiscoverCharacteristicsForService:error:", "characteristics", 5);
        register(clazz, "peripheral:didDiscoverDescriptorsForCharacteristic:error:", "descriptors", 5);
        register(clazz, "peripheral:didUpdateValueForCharacteristic:error:", "value", 5);
        register(clazz, "peripheral:didUpdateValueForDescriptor:error:", "descriptorValue", 5);
        nativeCall("objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS), clazz);
        delegate = message(clazz, "new");
        System.out.println("Read-only Xbox GATT probe; no characteristic writes or notification subscriptions");
        System.out.println("CoreBluetooth authorization: " + number(type("CBManager"), "authorization") + " (3=allowed)");
        central = message(message(type("CBCentralManager"), "alloc"), "initWithDelegate:queue:", delegate, MemorySegment.NULL);
        MethodHandle runLoop = linker.downcallHandle(core.find("CFRunLoopRunInMode").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_BYTE));
        MemorySegment mode = core.find("kCFRunLoopDefaultMode").orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!done && System.nanoTime() < deadline) runLoop.invokeWithArguments(mode, 0.25, (byte) 0);
        if (!done) {
            System.out.println("Probe timed out; pending operations=" + pending + ", selected Xbox=" + (peripheral.address() != 0));
            failed = true;
        }
        message(central, "stopScan");
        if (peripheral.address() != 0) message(central, "cancelPeripheralConnection:", peripheral);
        message(central, "setDelegate:", MemorySegment.NULL);
        if (peripheral.address() != 0) message(peripheral, "setDelegate:", MemorySegment.NULL);
        message(central, "release");
        message(delegate, "release");
        if (peripheral.address() != 0) message(peripheral, "release");
        message(pool, "drain");
        // Leave callback stubs alive until process exit; CoreBluetooth may finish asynchronous cleanup.
        if (failed) System.exit(1);
    }
}
