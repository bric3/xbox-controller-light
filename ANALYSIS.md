# Xbox button LED investigation

Recorded: 2026-10-04. Scope: central white Xbox button on the connected Series X|S controller, using this Mac over Bluetooth Low Energy.

## Findings

- No working Bluetooth brightness command or LED state readback was found.
- macOS omits HID service `1812` from CoreBluetooth. Fresh-pairing HCI capture proves the controller advertises it.
- The controller's raw 283-byte HID Report Map exactly matches the macOS descriptor and three reference dumps. It declares input report `01` and rumble output report `03`, with no feature reports or LED usages.
- All 24 tested GIP LED transport variants were rejected by macOS. A zero-strength rumble output report was accepted.
- Firmware, battery, and both readable vendor values agree byte for byte between CoreBluetooth and captured ATT responses.
- The separate vendor GATT service remains undocumented. Its writable characteristic has not been tested.

These findings establish the limits of the tested interfaces. They do not prove that every possible Bluetooth brightness command is absent.

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

The GameController and SDL capability results are recorded in [README.md](README.md). Their original one-off probe programs are not retained in this checkout. The retained Rust and Java probes are linked above.

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

## Commands and evidence reconstruction

Read-only inspection:

```sh
./target/release/xbox-light list
./target/release/xbox-light info
./target/release/xbox-light diagnose
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java hid
```

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

## Logging setup and cleanup

- Profile: `Bluetooth_macOS.mobileconfig`, from [Apple Profiles and Logs]; downloaded alongside [Bluetooth logging instructions].
- Profile payload inspected with OpenSSL CMS and `plutil`; instruction PDF extracted with Apple PDFKit through the local Java helper.
- `security cms -D` failed in the sandbox while attempting certificate import. OpenSSL `cms -verify -noverify` decoded the profile and verified content integrity without validating certificate-chain trust. The native macOS installer handled profile review.
- Profile enables system-wide private/debug Bluetooth logging, HCI tracing, and firmware logging; description specifies macOS 12+ and automatic removal after three days.
- Apple instructions require a reboot. On this Mac, logging became active after installation without a reboot.
- Profile removed after capture. Generated `/Library/Preferences/Logging/Subsystems/com.apple.bluetooth.plist` override confirmed absent; no PacketLogger process remained.
- Raw captures and decoded logs contain Bluetooth encryption key material and device identifiers. Files were restricted to `0600`; raw-file copies were verified byte-identical.
- Evidence and helpers under `target/` are local build artifacts excluded by `.gitignore`; this analysis file records their results separately.

## Untested and unresolved

- USB/GIP LED control on this controller from this Mac.
- Persistence after USB disconnect, Bluetooth reconnect, controller power cycle, or host program exit on firmware `5.9.2709.0`.
- Meaning of vendor characteristics `00000002`, `00000003`, and `00000004`.
- A successful Bluetooth LED write, brightness readback, or persistent-save command.
- Independent live GATT scan on Linux/ESP32; no extra hardware was available. The raw Mac capture supplied the Report Map comparison instead.

[xone] and [xbledctl] document temporary GIP settings and host-side reapplication. Their behavior suggests that switching from USB to Bluetooth would reset brightness, but this is an inference, not a test on this controller. A successful USB brightness change would establish hardware support through GIP; Bluetooth would still require its own accepted command/interface.

The [xbledctl worker] sends a mode/brightness command and closes the handle. This supports the inference that the controller can continue the effect without continuous host writes while connected; persistence after disconnect remains a separate question.

Remaining Bluetooth lead: documented vendor command or a capture from software known to change this model's brightness over Bluetooth. There is currently no evidence that the writable vendor characteristic performs that function.

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
