# Bluetooth on macOS

Updated: 2026-10-15. Initial investigation: 2026-10-04. Target: the central
white Xbox button on Microsoft Series X|S `045e:0b13`, firmware `5.9.2709.0`,
over Bluetooth Low Energy on this Mac.

Current status: Bluetooth LED control does not work. No brightness, glow
or LED readback command has succeeded. The Java app detects Bluetooth and
reports unsupported LED control through a separate handler.

[Analysis index](ANALYSIS.md) | [Firmware reverse engineering](FIRMWARE.md) | [Bluetooth on macOS](BLUETOOTH-MACOS.md) | [USB](USB.md)

## Findings

- No working Bluetooth brightness command or LED state readback was found.
- macOS omits HID service `1812` from CoreBluetooth. Fresh-pairing HCI capture proves the controller advertises it.
- The controller's raw 283-byte HID Report Map exactly matches the macOS descriptor and three reference dumps. It declares input report `01` and rumble output report `03`, with no feature reports or LED usages.
- All 24 tested GIP LED transport variants were rejected by macOS. A zero-strength rumble output report was accepted.
- Firmware, battery, and both readable vendor values agree byte for byte between CoreBluetooth and captured ATT responses.
- Four GIP LED packets sent to the vendor writable characteristic were acknowledged
  on firmware `5.9.2709.0`. The user observed no brightness change.
- Private CoreBluetooth service discovery and a direct Report Map read both failed
  with `The specified UUID is not allowed for this operation.`

These findings establish the limits of the tested interfaces. They do not prove that every possible Bluetooth brightness command is absent.

## Driver and direct GATT investigation: 2026-10-15

Reviewed the remapper at revision `b117ce010cf3d21cab6806b55fd890943cd33783`,
its 13 issue bodies and relevant replies, all 19 discussion topics, and its
empty pull request history. Its [LED discussion](https://github.com/Adam777Z/xbox-controller-button-remapper/discussions/16)
leads to SDL and xbledctl; it supplies no working Bluetooth command.

The user's [Steam driver example](https://steamcommunity.com/discussions/forum/1/3819656548992688579/?ctp=10#c836125171214496655)
confirms a Windows user needed the Xbox Extended Feature Support Driver.
The instructions quoted in that same comment explicitly limit the method to
USB and the Xbox Wireless Adapter and exclude Bluetooth. SDL revision
`6defbc0129b5ba90bab3f6c67bbc1e9329e24ff1` has a
[Steam Xbox backend wrapper](https://github.com/libsdl-org/SDL/blob/6defbc0129b5ba90bab3f6c67bbc1e9329e24ff1/src/hidapi/SDL_hidapi_steamxbox.h)
and [conditional backend wiring](https://github.com/libsdl-org/SDL/blob/6defbc0129b5ba90bab3f6c67bbc1e9329e24ff1/src/hidapi/SDL_hidapi.c).
Its referenced `steamxbox/hid.c` implementation is absent from that public tree.

Valve's [Windows client manifest](https://client-update.akamai.steamstatic.com/steam_client_win32)
supplied `bins_win32.zip.23e34a6d4b10596a44561a5100dac5585d2517da`.
Its SHA-256 matched the manifest:
`8b712b2a3412a9066b7725f4e1c5cef9a7ca5b187b6585a5b92d25d09df0ba62`.
The embedded `bin/drivers.exe` archive contains x64 `steamxbox.sys` version
`1.0.0.16`, SHA-256
`24a7924f33dcb3b122efcaf1b425d06f05b99f9456ca1ab3b850901939be5e16`.
It was extracted and analysed with Ghidra; no Windows binary was executed.

The driver does contain Bluetooth handlers. Device creation identifies
`BTHLEDEVICE` and assigns connection transport 4. Packet submission at
`0x140003400` branches on that transport: the BLE branch at `0x140003546`
forwards the supplied buffer and length through `WdfIoTargetSendIoctlSynchronously`,
IOCTL `0x000b000f` ([HID write report](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/hidport/ni-hidport-ioctl_hid_write_report)).
The classic Bluetooth branch prepends `a2` and submits an interrupt transfer.
Neither traced branch converts a GIP LED command into a vendor GATT command.
This identifies the driver's lower-level transport path, without demonstrating
a Bluetooth brightness command. The driver was not exercised on Windows.

Additional [firmware execution checks](FIRMWARE.md#bluetooth-vendor-service)
show that the vendor command is ignored in the connected Bluetooth state of
`5.23.6.0`. The HID output callback rejects a seven-byte GIP packet and treats
an eight-byte or longer write as rumble. The HID Control Point only logs its
event. Bypassing macOS's report filtering would still reach these firmware
handlers in that version. Installed `5.9.2709.0` remains a separate test target.

Local Objective-C inspection and CoreBluetooth disassembly found private
`CBService.initWithPeripheral:dictionary:` and `CBPeripheral.setAttribute:forHandle:`
methods. The read-only GATT probe now has a `hidden-hid` mode which constructs
the captured HID service range `0016`-`0023` and requests its characteristics
directly. When discovery is rejected, it constructs `CBCharacteristic` for
Report Map `2A4B`, declaration handle `001b`, value handle `001c`, and reads it
directly. It requires matching PnP bytes and firmware `5.9.2709.0` before using
those handles. A local self-check verifies both constructors, UUIDs, properties
and handles. Both access paths were rejected on the connected controller.

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java --self-test
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java hidden-hid 600
```

The optional wait is 1-600 seconds, default 15. During the wait, the probe scans
and checks already-connected peripherals every five seconds, including devices
macOS reconnects before an advertisement reaches the probe. It cannot wake a
powered-off controller. This mode sends no characteristic writes, including
vendor writes, and does not subscribe to notifications.

The initial sandboxed run returned CoreBluetooth authorization 2 and state 2.
Outside the sandbox, authorization was 3 and state 5, but no Xbox peripheral
was connected or discovered during waits of 600, 120 and 60 seconds. SIP was
enabled and the account had standard privileges. The private constructor
self-check and firmware checks passed. The user subsequently powered on the
controller, allowing the live tests below.

Evidence: [remapper discussions and PR inventory](target/led-project-research/remapper-research.json),
[Steam thread](target/led-project-research/steam-led-page10.html),
[Valve package manifest](target/led-project-research/steam_client_win32.manifest),
[Steam driver decompilation](target/led-project-research/steamxbox-decompiled.c),
[driver strings and references](target/led-project-research/steamxbox-data.txt),
[driver Ghidra analysis log](target/led-project-research/steamxbox-analysis.log),
[native method inventory](target/led-project-research/bluetooth-runtime.txt),
[CoreBluetooth disassembly](target/led-project-research/corebluetooth-disassembly.txt),
[live hidden HID attempt](target/led-project-research/hidden-hid-live.txt),
[final reconnection check](target/led-project-research/hidden-hid-final.txt).

## Hidden HID and vendor write tests: 2026-10-15

The controller reconnected as `045e:0b13`, firmware `5.9.2709.0`. Constructing
the hidden service succeeded locally, but characteristic discovery returned
`The specified UUID is not allowed for this operation.` A second attempt
constructed the captured Report Map characteristic and requested its value
directly. That read returned the same error and zero bytes. Private constructors
alone do not provide access to this HID service through CoreBluetooth.

The separate [vendor probe](target/XboxVendorLed.java) verified PnP identity,
firmware and the writable characteristic before sending four seven-byte GIP
LED packets to `00000004-5F60-4C4F-9C83-A7953298D40D`, with response, three
seconds apart:

```text
0a 20 01 03 00 01 00
0a 20 02 03 00 01 14
0a 20 03 03 00 01 00
0a 20 04 03 00 01 14
```

These request steady brightness steps 0, 20, 0 and 20. All four writes
completed without an ATT error. The user watched the entire sequence and
reported no visible change. This rules out these GIP packets as a working
LED command on that characteristic in the tested state; an acknowledgement
does not establish that the controller interpreted the payload as LED control.

IOBluetooth disassembly also found that `BluetoothHCISendRawACLData`,
`BluetoothHCISendRawCommand` and `sendRawHCIRequest` are each just
`mov w0, #0; ret` in this macOS build. They report success without sending
anything. These legacy exports therefore cannot supply the raw transport;
they were not called. No sudo or SIP changes were used for these tests.

Verification: service and characteristic constructor self-checks passed;
the vendor packet self-check passed; the live vendor probe received 4/4
successful write callbacks. Hidden HID reads failed as described above.
No Bluetooth brightness control or LED readback was achieved.

Evidence: [hidden service attempt](target/led-project-research/hidden-hid-awake.txt),
[direct Report Map attempt](target/led-project-research/hidden-hid-direct-read.txt),
[vendor write results](target/led-project-research/vendor-led-live.txt),
[IOBluetooth disassembly](target/led-project-research/iobluetooth-disassembly.txt).

## Built-in radio and driver replacement: 2026-10-15

The required transport is the Mac's built-in radio, with SIP enabled. No
working access workaround has been established under those constraints.

The live IORegistry identifies `BTLEServer`, PID 739, as the creator of the
Xbox `IOHIDUserDevice`. Its child `IOHIDInterface` is matched by
`IOHIDEventDummyService`, published by `AppleGameControllerPersonality`.
The built-in radio uses `AppleBluetoothModule` and
`AppleConvergedIPCOLYBTControl`; the Xbox HID interface is above that transport.

```text
HID app or prospective HIDDriverKit driver
  -> IOHIDUserDevice
  -> BTLEServer: report ID/type -> discovered GATT characteristic
  -> Apple's Bluetooth stack -> built-in radio -> Xbox controller
```

Apple's published [IOHIDInterface implementation](https://github.com/apple-oss-distributions/IOHIDFamily/blob/main/IOHIDFamily/IOHIDInterface.cpp)
forwards DriverKit `SetReport` to its owning HID device.
[IOHIDUserDevice](https://github.com/apple-oss-distributions/IOHIDFamily/blob/main/IOHIDFamily/IOHIDUserDevice.cpp)
then forwards to its user-space provider. Local arm64e disassembly confirms
that `BTLEServer`'s `HIDService.writeReportData:reportID:reportType:withResponse:error:`
at `0x10001fb04` looks up the corresponding discovered characteristic. A missing
lookup returns `0xe00002f0`, the same error seen in the live LED report tests.
Replacing the HID event handler therefore leaves this report mapping in place.
It does not expose arbitrary ATT handles or replace the Bluetooth transport.

The direct CoreBluetooth read at `0x199b9ded4` sends characteristic declaration
and value handles in message `0xcb`, without a caller-supplied UUID. Changing
the synthetic characteristic's UUID would not change that request.
`CBManager.sendRawCommand:data:completionHandler:` at `0x199bd35c0` is another
`mov w0, #0; ret` stub in this build.

`BTLEServer` carries `com.apple.BTServer.allowRestrictedServices`, also present
as a string in `bluetoothd`. An isolated copy of the Java launcher was locally
signed with that entitlement and the ordinary JIT/library entitlements. Running
only `java -version` terminated with status 137, including outside the tool
sandbox. An otherwise identical locally signed control ran successfully.
Removing the Bluetooth entitlement from the original test path also restored
successful execution. `codesign --verify --strict` accepted the restricted
binary on disk, and SIP remained enabled. This establishes a launch failure
associated with claiming the entitlement locally; it does not test an
Apple-authorized provisioning profile. The system log store was inaccessible
to this standard account, so the exact validation diagnostic was not obtained.

A custom HIDDriverKit extension can use SIP-compatible installation when it
has [Apple-granted entitlements and matching signing/provisioning](https://developer.apple.com/documentation/driverkit/requesting-entitlements-for-driverkit-development).
That approval does not grant `BTLEServer`'s private restricted-service access.
No public DriverKit interface for replacing this built-in Bluetooth transport
was found. Apple's [driver guidance](https://developer.apple.com/documentation/driverkit/creating-a-driver-using-the-driverkit-sdk)
excludes Bluetooth radio functionality from USBDriverKit, and its
[kernel-extension guidance](https://developer.apple.com/support/kernel-extensions/)
deprecates the older Bluetooth HID interfaces. A signed kernel extension can
coexist with SIP, but Apple Silicon requires Recovery approval of Reduced
Security and third-party kernel extensions, plus restart. That is a separate
security policy from SIP and cannot be supplied by Privileges alone.
See [Apple's deployment requirements](https://support.apple.com/guide/deployment/depa5fb8376f/web).
No kernel transport implementation or compatible signing profile was verified.

If a useful DriverKit implementation becomes viable, clean installation and
removal should use an app containing the extension and Apple's
[activation/deactivation requests](https://developer.apple.com/documentation/systemextensions/installing-system-extensions-and-drivers).
Match only the intended Xbox interface. Uninstall must wait for completion,
report any [required restart](https://developer.apple.com/documentation/systemextensions/ossystemextensionrequest/result/willcompleteafterreboot),
and verify that Apple's handler resumes after reconnecting the controller.
This lifecycle is a design requirement, not a tested installer. No driver was
installed, no system service was stopped, and the installed JDK was unchanged.
The temporary executable copies were removed after the launch experiment.

Evidence: [HID producer and owner](target/led-project-research/hid-producer-tree.txt),
[service tree](target/led-project-research/ioservice-tree.txt),
[radio driver](target/led-project-research/builtin-radio-driver.txt),
[BTLEServer entitlements](target/led-project-research/btleserver-entitlements.txt),
[BTLEServer arm64e disassembly](target/led-project-research/btleserver-arm64e.txt),
[entitlement experiment](target/led-project-research/restricted-entitlement-test.txt).

## Live Bluetooth retry: 2026-10-15

After the user switched to Bluetooth, a fresh GATT scan again identified
`045e:0b13`, firmware `5.9.2709.0`, and the same three CoreBluetooth-visible
services. Shared HID accepted the nine-byte zero-strength rumble control.
Four LED output candidates were then rejected with `0xe00002f0`: seven-byte GIP
and padded 13-byte USB buffers, each with HID metadata report IDs `0x0a` and `1`.
There was no accepted LED write from which to start the breathing animation.
This is a macOS HID rejection, without proof of delivery to the controller.

The utility now detects the transport before dispatch. Its USB packet, send,
animation and cache/write methods are unchanged. The separate Bluetooth handler
reports that brightness and glow are unavailable, without writing or accessing
the USB cache. `--inspect` succeeds for a connected Bluetooth controller.
Checks covered Bluetooth inspection, all six LED command forms, unchanged USB
state, argument validation and transport selection. USB hardware was disconnected
during this change; USB regression checks compared the existing methods and
reran packet, animation and state self-tests. The user subsequently confirmed
that USB brightness step 5 still works; see [USB.md](USB.md).

Evidence: [GATT refresh](target/firmware-analysis/bluetooth-gatt-refresh.txt),
[HID packet attempts](target/firmware-analysis/bluetooth-led-probe.txt),
[Bluetooth CLI checks](target/firmware-analysis/bluetooth-led-cli-check.txt).

## Firmware comparison

[Firmware reverse engineering](FIRMWARE.md#bluetooth-vendor-service) identifies
vendor characteristics `00000002` as telemetry, `00000003` as a host-list read,
and `00000004` as a seven-byte write to host-manager task 29. The standard
Bluetooth HID output callback handles rumble. Neither traced callback
provides a GIP tunnel or a verified brightness command.

These findings concern downloaded `5.23.6.0`. The installed `5.9.2709.0`
image remains unavailable, so the exact vendor-command semantics on this
controller are unresolved. The four live vendor writes above produced no
visible LED change on the installed version.

## Environment

| Item              | Value                                                      |
| ----------------- | ---------------------------------------------------------- |
| Controller        | Xbox Series X\|S, Microsoft `045e:0b13`                    |
| Firmware          | `5.9.2709.0`, confirmed through IORegistry and GATT `2A26` |
| Connection        | Bluetooth Low Energy, HID over GATT                        |
| macOS             | `26.7.1`, build `25G241`                                   |
| Xcode             | `27.0`, build `27A266a`                                    |
| Rust              | `1.99.0`; project minimum `1.85`, edition 2024             |
| HIDAPI Rust crate | `2.6.7`, `macos-shared-device` feature                     |
| SDL               | `3.4.16`, installed library directory `/opt/homebrew/lib`  |
| Java              | Amazon Corretto `25.0.4`, selected through `mise`          |
| PacketLogger CLI  | `26.0`, bundled in Additional Tools for Xcode 27           |

## Tools and origins

| Tool                                                                 | Origin                                                                         | Use                                                                                                   |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| [xbox-light](src/main.rs)                                            | Local Rust CLI; [hidapi-rs] crate                                              | Identity, coarse battery reports, HID descriptor, input capture                                       |
| [home_led_probe.rs](examples/home_led_probe.rs)                      | Local Rust example; packet derived from [SDL driver]                           | Single brightness write and bounded 24-variant search                                                 |
| GameController framework probe                                       | Apple system framework; [GCController light] API                               | Controller detected; `light = nil`                                                                    |
| SDL capability probe                                                 | Installed SDL 3.4.16; [SDL driver] source                                      | Open controller and inspect LED capability                                                            |
| [XboxGattProbe.java](tools/XboxGattProbe.java)                       | Local Java 25 FFM code calling Apple's [CoreBluetooth] and Objective-C runtime | Read-only service, included-service, characteristic, descriptor, and value discovery                  |
| `ioreg`                                                              | macOS command-line utility                                                     | Read published firmware property `kBTFirmwareRevisionKey`                                             |
| PacketLogger                                                         | [Apple Bluetooth tools]; user-downloaded Additional Tools for Xcode 27 DMG     | Live HCI capture and offline ATT decoding                                                             |
| Bluetooth logging profile                                            | [Apple Profiles and Logs]; `Bluetooth_macOS.mobileconfig`                      | Enable private Bluetooth diagnostic and HCI logging                                                   |
| [SummarizeXboxAtt.java](target/SummarizeXboxAtt.java)                | Local Java helper                                                              | Reassemble reads, decode service/characteristic declarations, compare GATT values, extract Report Map |
| [CompareXboxDescriptors.java](target/CompareXboxDescriptors.java)    | Local Java helper                                                              | Byte comparison, SHA-256, input/output/feature report IDs                                             |
| [ReadPdf.java](target/ReadPdf.java), OpenSSL, `plutil`               | Local Java helper calling Apple PDFKit; installed native utilities             | Inspect Apple's logging instructions and signed profile payload                                       |
| [ProtectXboxCaptures.java](target/ProtectXboxCaptures.java), `chmod` | Local Java helper and native utility                                           | Preserve capture bytes while restricting files to mode `0600`                                         |

The GameController and SDL capability results are summarised in the attempts
table below. Their original one-off probe programs are not retained in this
checkout. The retained Rust and Java probes are linked above.

PacketLogger download: `Additional_Tools_for_Xcode_27.dmg`, supplied under `/Users/brice.dutheil/Downloads/`. The DMG was mounted read-only, the app copied with `ditto` to `target/PacketLogger.app`, its Apple signature verified with `codesign`, and the DMG unmounted. Live capture required administrator privileges and was started by the user in Terminal. Offline conversion required no administrator privileges.

## Attempts and results

| Attempt                                           | Tool or interface                                           | Result                                                                                                                                                                                    |
| ------------------------------------------------- | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Inspect controller identity and reports           | Rust CLI / HIDAPI                                           | `045e:0b13`; 283-byte descriptor; no LED or feature reports. [Dump](target/controller-comparison.txt)                                                                                     |
| Read firmware outside HIDAPI                      | IORegistry / `ioreg`                                        | `kBTFirmwareRevisionKey = 5.9.2709.0`; CLI firmware field remains unavailable                                                                                                             |
| Use Apple's light abstraction                     | GameController framework                                    | Controller detected; light property unavailable (`nil`)                                                                                                                                   |
| Check SDL LED support                             | SDL 3.4.16                                                  | No RGB LED capability reported; that flag does not establish white-button LED support                                                                                                     |
| Inspect SDL home LED hint                         | [SDL driver] source                                         | GIP packet, no Bluetooth-specific translation, send result ignored, no LED query                                                                                                          |
| Request half SDL's normal brightness              | Rust example / HIDAPI output write                          | Packet rejected; brightness change and delivery unconfirmed                                                                                                                               |
| Try 24 bounded packet variants                    | Rust example / output and feature reports                   | All rejected with `0xE00002F0`; zero-strength rumble control accepted. [Log](target/led-sweep.txt)                                                                                        |
| Discover all, included, and explicit HID services | CoreBluetooth Java probe                                    | Three exposed services; zero included services; explicit `1812` returns zero without an error. [All](target/gatt-pairing-capture.txt), [HID only](target/gatt-hid-pairing-capture.txt)    |
| Compare published descriptors                     | Java descriptor helper                                      | macOS descriptor and all three reference dumps identical. [Comparison](target/descriptor-comparison.txt)                                                                                  |
| Capture discovery below CoreBluetooth             | PacketLogger with profile, reconnection, then fresh pairing | Fresh pairing captured six primary services and full Report Map; details below                                                                                                            |
| Compare raw controller data with macOS views      | Java ATT and descriptor helpers                             | Seven characteristic values match; raw Report Map matches macOS and reference dumps. [ATT](target/xbox-hci-pairing-analysis.txt), [descriptor](target/xbox-hci-report-map-comparison.txt) |

### LED packet and bounded search

Packet tested:

```text
0a 20 00 03 00 01 0a
```

`0a` is the GIP LED command; mode `01` requests steady illumination; final byte `0a` is brightness step 10. SDL normally uses step 20. This test requested half that normal value, not a percentage or a verified physical maximum.

SDL uses `MAX_VALUE = 50` to scale decimal hint values; it is not a measured maximum for this controller. Hint `"0.2"` computes step 10; `"0.5"` computes step 25, not half the default 20. [SDL driver]

The search kept opcode, mode, and brightness fixed and varied:

| Dimension                       | Values                            |
| ------------------------------- | --------------------------------- |
| HIDAPI operation                | Output write; feature report      |
| GIP sequence                    | `0`; `1`                          |
| GIP packet length               | `7`; `9`; `64`, padded with zeros |
| HIDAPI unnumbered-report prefix | Absent; leading `00`              |
| Total                           | `2 x 2 x 3 x 2 = 24` candidates   |

Output-access control: SDL Bluetooth rumble packet with all motor strengths zero:

```text
03 0f 00 00 00 00 ff 00 eb
```

The rumble control accepted all nine bytes. Every LED candidate produced:

```text
IOHIDDeviceSetReport failed: (0xE00002F0) (iokit/common) data was not found
```

This is a macOS API rejection, not a captured controller rejection of an LED command. No accepted LED write or visual brightness change was established.

The probe stops at the first accepted LED candidate for visual inspection, an
incomplete write, or any error other than macOS's missing-report rejection.
If every candidate is rejected, it exits with code 1. Acceptance alone would
not prove a brightness change. The example sends no vendor GATT commands.

### Bluetooth captures

| Capture                                                       | File size    | Xbox ATT packets | Result                                                                                 |
| ------------------------------------------------------------- | ------------ | ---------------- | -------------------------------------------------------------------------------------- |
| [Initial](target/xbox-hci-first.pkl)                          | 260 bytes    | 0                | Only PacketLogger's final disconnect note; no usable HCI/ATT evidence                  |
| [Logging profile enabled](target/xbox-hci-profile.pklg)       | 110575 bytes | 436              | Reads and input notifications; no primary discovery or Report Map read                 |
| [Power off and reconnect](target/xbox-hci-reconnect.pklg)     | 81184 bytes  | 445              | Identity/battery reads and notification setup; no primary discovery or Report Map read |
| [Forget pairing and pair again](target/xbox-hci-pairing.pklg) | 88498 bytes  | 270              | Six primary services, HID characteristics, report references, and full Report Map      |

Packet counts refer to Xbox ATT traffic, including packets labelled `ACL` whose L2CAP channel is `0004`. They exclude other devices and other protocols.

Ordinary reconnection retained the known service handles. Fresh pairing triggered the missing discovery. The controller negotiated ATT MTU 23; long values arrived as Read Response plus Read Blob Response chunks.

The connected and reconnection probes returned ATT `Attribute Not Found` for included-service requests in each exposed service range. Raw reads reproduced all seven characteristic values in the matching CoreBluetooth logs. macOS enabled input and battery notifications through `01 00` writes to CCC descriptors `001F` and `0015`; the Java probe itself made no writes or notification subscriptions.

The profile-enabled capture includes 366 input notifications at handle `001E`.
Its [decoded summary](target/xbox-hci-profile-analysis.txt) reproduces the seven
readable values but contains no primary discovery or Report Map read. The first
capture's [decoded output](target/xbox-hci-first.txt) contains only PacketLogger's
disconnect note. Fresh pairing supplied the missing discovery.

### Discovered primary services

| Service UUID                           | Handle range  | CoreBluetooth exposed it |
| -------------------------------------- | ------------- | ------------------------ |
| `1800` Generic Access                  | `0001`-`0007` | No                       |
| `1801` Generic Attribute               | `0008`-`0008` | No                       |
| `180A` Device Information              | `0009`-`0011` | Yes                      |
| `180F` Battery                         | `0012`-`0015` | Yes                      |
| `1812` HID                             | `0016`-`0023` | No                       |
| `00000001-5F60-4C4F-9C83-A7953298D40D` | `0024`-`002A` | Yes                      |

### Discovered characteristics

Properties: `R` read; `W` write with response; `WNR` write without response; `N` notify. Vendor UUIDs below share suffix `-5F60-4C4F-9C83-A7953298D40D`.

| Service | Characteristic UUID      | Value handle | Properties | Observation                                                |
| ------- | ------------------------ | ------------ | ---------- | ---------------------------------------------------------- |
| `180A`  | `2A29` Manufacturer      | `000B`       | R          | Microsoft, 9 bytes                                         |
| `180A`  | `2A50` PnP ID            | `000D`       | R          | `02 5e 04 13 0b 09 05`, 7 bytes                            |
| `180A`  | `2A26` Firmware          | `000F`       | R          | `5.9.2709.0`, 10 bytes                                     |
| `180A`  | `2A25` Serial            | `0011`       | R          | 14 bytes                                                   |
| `180F`  | `2A19` Battery Level     | `0014`       | R, N       | Probe read `100`; percentage, not remaining runtime        |
| `1812`  | `2A4A` HID Information   | `0018`       | R          | `01 01 00 03`                                              |
| `1812`  | `2A4C` HID Control Point | `001A`       | WNR        | Standard HID control point; not established as LED control |
| `1812`  | `2A4B` Report Map        | `001C`       | R          | 283 bytes                                                  |
| `1812`  | `2A4D` Input Report      | `001E`       | R, N       | Report reference `01 01` at `0020`                         |
| `1812`  | `2A4D` Output Report     | `0022`       | R, W, WNR  | Report reference `03 02` at `0023`; rumble                 |
| Vendor  | `00000002`               | `0026`       | R          | 522 bytes; meaning unknown                                 |
| Vendor  | `00000003`               | `0028`       | R          | 32 bytes; meaning unknown                                  |
| Vendor  | `00000004`               | `002A`       | W          | Meaning unknown; no writes attempted                       |

Battery initially reported `50` during fresh pairing, then `100` during the probe. Both values appear in raw traffic; no explanation for this transition was established.

### Descriptor comparison

Extracted map: [xbox-hci-pairing-report-map.bin](target/xbox-hci-pairing-report-map.bin), 283 bytes.

```text
SHA-256: a681e2cb8b5d9466f258e2f07f0fcc518ec438231af1b61b125c213d2c62e165
Reports: input=[1], output=[3], feature=[]
```

Byte-identical comparisons:

- macOS HIDAPI descriptor in [controller-comparison.txt](target/controller-comparison.txt).
- [xpadneo descriptor], downloaded as [xpadneo-xbxs.md](target/xpadneo-xbxs.md).
- [ControllersInfo model 1914 BLE], downloaded as [controllersinfo-1914-ble.bin](target/controllersinfo-1914-ble.bin).
- [ControllersInfo model 1914 firmware 5.17], downloaded as [controllersinfo-1914-fw517.bin](target/controllersinfo-1914-fw517.bin).

## Repository and protocol comparisons

| Source                                | Relevant finding                                                                                              | Scope                                                     |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| [SDL driver]                          | Home LED hint emits GIP `0a`; Bluetooth callback uses the same bytes; no state query                          | Source inspection plus local HIDAPI write tests           |
| [xone LED control], [xone LED helper] | GIP LED brightness control; driver mode readback is cached; documented settings last until disconnect         | USB/Xbox Wireless; source reviewed, driver not run        |
| [xbledctl]                            | Windows XboxGIP LED control; host profiles reapplied after reconnect; worker sends command then closes handle | USB; source reviewed, application not run                 |
| [TheNathannator protocol notes]       | GIP packet reference; explicitly marked obsolete in favor of [Microsoft MS-GIPUSB]                            | USB protocol reference; not proof of a Bluetooth command  |
| [TheNathannator Windows driver notes] | Windows driver interface differs from raw transport                                                           | Windows; not a macOS HIDAPI interface                     |
| [xpadneo LED discussion]              | Maintainer reports removal of Bluetooth HID vendor extensions; older speculation includes Elite Series 2      | Source claim; not a verified Series X\|S BLE LED command  |
| [Bluepad32 Xbox parser]               | Bluetooth HID input and rumble implementation                                                                 | No verified LED route identified in reviewed code         |
| [ESP32 Xbox library]                  | Alternative HIDS/GATT implementation                                                                          | Source comparison only; no ESP32 hardware available       |
| [SDL GameControllerDB]                | Input/button/axis mappings                                                                                    | Does not supply LED commands or GATT protocol definitions |
| [Dear ImGui]                          | GUI library                                                                                                   | No controller protocol or LED implementation              |

The supplied [Steam discussion] was treated as a lead. It was not used as protocol evidence.

[xone's compatibility notes](https://github.com/medusalix/xone#compatibility)
exclude Bluetooth. [xbledctl's protocol description](https://github.com/Leclowndu93150/xbledctl#how-it-works)
covers Windows XboxGIP USB access. Neither supplies a verified LED path for this
macOS Bluetooth connection.

The Rust CLI's controller filter follows
[SDL's controller identities](https://github.com/libsdl-org/SDL/blob/main/src/joystick/usb_ids.h).
Its coarse battery interpretation follows the
[SDL Xbox One HIDAPI driver](https://github.com/libsdl-org/SDL/blob/main/src/joystick/hidapi/SDL_hidapi_xboxone.c),
without treating the approximate percentage mapping as a precise measurement.

## Commands and evidence reconstruction

Read-only inspection:

```sh
./target/release/xbox-light list
./target/release/xbox-light info
./target/release/xbox-light diagnose
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java hid
```

The probe selects the first connected peripheral whose name contains `Xbox`.
Use it with one Xbox controller connected. If none is returned by the connected
device lookup, it scans advertisements within a 15-second deadline. It discovers
services, recursively discovers their included services, discovers characteristics
and descriptors, and reads characteristics marked readable. It sends no
characteristic writes and enables no notifications. Discovery/read errors or a
timeout cause exit code 1.
Output includes the controller's serial number and opaque vendor data; inspect
it before sharing. macOS Bluetooth permission is required.

LED experiments, which send output/feature reports when not using `--dry-run`:

```sh
CARGO_HOME="$PWD/target/cargo-home" cargo run --offline --locked --example home_led_probe
CARGO_HOME="$PWD/target/cargo-home" cargo run --offline --locked --example home_led_probe -- --sweep --dry-run
CARGO_HOME="$PWD/target/cargo-home" cargo run --offline --locked --example home_led_probe -- --sweep
```

Capture procedure used for the fresh-pairing trace: start the command below; forget only the Xbox controller's Mac pairing; pair it again; run both read-only probes; stop capture with Ctrl+C.

```sh
sudo ./target/PacketLogger.app/Contents/Resources/packetlogger convert -b -o target/xbox-hci-pairing.pklg
```

Offline decoding and comparison:

```sh
./target/PacketLogger.app/Contents/Resources/packetlogger convert -i target/xbox-hci-pairing.pklg -o target/xbox-hci-pairing-compact.txt -f itpnhdsr
./target/PacketLogger.app/Contents/Resources/packetlogger convert -i target/xbox-hci-pairing.pklg -o target/xbox-hci-pairing.txt -f imtpnhdsfrl
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang target/SummarizeXboxAtt.java target/xbox-hci-pairing-compact.txt target/gatt-pairing-capture.txt
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang target/CompareXboxDescriptors.java target/xbox-hci-pairing-report-map.bin target/controller-comparison.txt target/xpadneo-xbxs.md target/controllersinfo-1914-ble.bin target/controllersinfo-1914-fw517.bin
```

The ATT helper also accepts an optional third argument containing the Xbox packet name/address for captures labelled by address. It validates unfragmented HCI ACL/L2CAP records, joins contiguous read chunks, checks seven values against the matching GATT log, and exports a discovered Report Map. It assumes this controller's characteristic handles; it is not a general Bluetooth decoder.

## Local verification

Previously completed local checks: seven Rust tests passed (six CLI tests and one LED-candidate test), formatting passed, Clippy passed with warnings denied, and the release build completed. These checks validate the software; they do not establish hardware LED support.

```sh
cargo test --locked --all-targets
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo build --release --locked
```

Both CoreBluetooth probe modes completed successfully after fresh pairing. The ATT helper verified all seven readable characteristic values against the matching probe output. The descriptor helper and `cmp` verified the extracted Report Map against the reference data.

The tests cover CLI validation, device filtering and selection, recognised,
truncated and unknown battery reports, and LED candidate opcode and brightness.
They use synthetic inputs and do not establish that a controller exposes those
reports. Further manual checks include input capture while a game uses shared
access, multiple-controller selection and errors after disconnecting a device.

## Logging setup and cleanup

- Profile: `Bluetooth_macOS.mobileconfig`, from [Apple Profiles and Logs]; downloaded alongside [Bluetooth logging instructions].
- Profile payload inspected with OpenSSL CMS and `plutil`; instruction PDF extracted with Apple PDFKit through the local Java helper.
- `security cms -D` failed in the sandbox while attempting certificate import. OpenSSL `cms -verify -noverify` decoded the profile and verified content integrity without validating certificate-chain trust. The native macOS installer handled profile review.
- Profile enables system-wide private/debug Bluetooth logging, HCI tracing, and firmware logging; description specifies macOS 12+ and automatic removal after three days.
- Apple instructions require a reboot. On this Mac, logging became active after installation without a reboot.
- Profile removed after capture. Generated `/Library/Preferences/Logging/Subsystems/com.apple.bluetooth.plist` override confirmed absent; no PacketLogger process remained.
- Raw captures and decoded logs contain Bluetooth encryption key material and device identifiers. Files were restricted to `0600`; raw-file copies were verified byte-identical.
- Evidence and helpers under `target/` are local build artifacts excluded by `.gitignore`; this analysis file records their results separately.

## Unresolved

- A successful Bluetooth brightness/glow command or LED readback.
- The exact meaning of vendor command `0x28` and its behaviour on installed
  firmware `5.9.2709.0`.
- Independent live GATT inspection on Linux or ESP32; neither was available.
  The raw Mac capture supplied the Report Map comparison.

Remaining leads are a documented Bluetooth vendor command or a capture from
software known to change this model's brightness over Bluetooth. USB success
does not establish a Bluetooth command; [USB.md](USB.md) records that path.

[hidapi-rs]: https://github.com/ruabmbua/hidapi-rs
[SDL driver]: https://github.com/libsdl-org/SDL/blob/release-3.4.16/src/joystick/hidapi/SDL_hidapi_xboxone.c
[GCController light]: https://developer.apple.com/documentation/gamecontroller/gccontroller/light
[CoreBluetooth]: https://developer.apple.com/documentation/corebluetooth
[Apple Bluetooth tools]: https://developer.apple.com/bluetooth/
[Apple Profiles and Logs]: https://developer.apple.com/feedback-assistant/profiles-and-logs/?name=bluetooth&platform=macos
[Bluetooth logging instructions]: https://developer.apple.com/services-account/download?path=%2FOS_X%2FOS_X_Logs%2FBluetooth_Logging_Instructions.pdf
[xone]: https://github.com/medusalix/xone
[xone LED control]: https://github.com/medusalix/xone#led-control
[xone LED helper]: https://github.com/medusalix/xone/blob/master/driver/common.c
[xbledctl]: https://github.com/Leclowndu93150/xbledctl
[xbledctl worker]: https://github.com/Leclowndu93150/xbledctl/blob/main/src/main.cpp#L355-L439
[TheNathannator protocol notes]: https://gist.github.com/TheNathannator/c5d3b41a12db739b7ffc3d8d1a87c60a
[TheNathannator Windows driver notes]: https://gist.github.com/TheNathannator/bcebc77e653f71e77634144940871596
[Microsoft MS-GIPUSB]: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-gipusb/e7c90904-5e21-426e-b9ad-d82adeee0dbc
[xpadneo descriptor]: https://github.com/atar-axis/xpadneo/blob/master/docs/descriptors/xbxs.md
[xpadneo LED discussion]: https://github.com/atar-axis/xpadneo/issues/18#issuecomment-2888891363
[ControllersInfo model 1914 BLE]: https://github.com/DJm00n/ControllersInfo/blob/master/xboxone/xboxone_model_1914_bluetoothle_hid_report_descriptor.bin
[ControllersInfo model 1914 firmware 5.17]: https://github.com/DJm00n/ControllersInfo/blob/master/xboxone/xboxone_model_1914_firmware_5_17_hid_report_descriptor.bin
[Bluepad32 Xbox parser]: https://github.com/ricardoquesada/bluepad32/blob/main/src/components/bluepad32/parser/uni_hid_parser_xboxone.c
[ESP32 Xbox library]: https://github.com/asukiaaa/arduino-XboxSeriesXControllerESP32/blob/main/src/XboxSeriesXControllerESP32_asukiaaa.hpp
[SDL GameControllerDB]: https://github.com/mdqinc/SDL_GameControllerDB/blob/master/gamecontrollerdb.txt
[Dear ImGui]: https://github.com/ocornut/imgui
[Steam discussion]: https://steamcommunity.com/discussions/forum/1/4133808627029005596/
