# xbox-light

A small Rust CLI to inspect an Xbox Wireless Controller connected to macOS over
Bluetooth. It lists controllers, reads available identity and battery information,
and captures HID descriptors and input reports for protocol investigation.

**This version cannot control the Xbox button light.** No Bluetooth LED command or
LED readback mechanism has been verified. The documented GIP LED command is used
over USB/Xbox Wireless, which is a different transport from Bluetooth. Firmware
readback is also unavailable through the implemented interface.

The first version was developed without hardware. Subsequent inspection of the
connected `045e:0b13` controller confirmed firmware `5.9.2709.0` in macOS's device
registry. The CLI does not currently read that registry property; this finding
is not a compatibility guarantee for other controllers or firmware.

See [ANALYSIS.md](ANALYSIS.md) for the attempts, tools, source links, captured
protocol data, and remaining unknowns.

## Build and install

Requires macOS, Rust 1.85 or newer, and Apple's Command Line Tools. Install them
with `xcode-select --install` if they are missing. HIDAPI's macOS implementation
is compiled into the binary; no separate HIDAPI installation is needed.

```sh
cargo build --release --locked
./target/release/xbox-light --help

# Optional: install into Cargo's bin directory.
cargo install --path . --locked
```

## Usage

Pair and connect the controller in macOS Bluetooth settings first. The CLI does
not pair controllers or reconnect them.

```sh
xbox-light list
xbox-light info
xbox-light diagnose > controller-diagnostics.txt

# With multiple matching interfaces, copy a path from list:
xbox-light info --device 'DevSrvsID:123456'
xbox-light diagnose --device 'DevSrvsID:123456' > controller-diagnostics.txt
```

`list` enumerates recognized Microsoft Xbox Bluetooth gamepad interfaces without
opening them. It includes name, vendor/product IDs, transport, path, serial when
available, HID usage, and the device release number. Paths are connection-specific;
run `list` again after reconnecting. USB interfaces and unrelated Microsoft HID
devices are excluded.

`info` prints the identity and listens for up to three seconds for the known
two-byte Xbox Bluetooth battery report (`04 flags`). The battery level is coarse:
low, medium, high, or full (levels 0-3). USB power is reported without inferring a
charging state or battery level. If no recognized report arrives, battery is
marked unavailable. A missing battery report does not mean the battery is empty.

`diagnose` prints the raw HID report descriptor, then captures input reports for
five seconds. Each report includes its elapsed timestamp, length, and hex bytes.
Move sticks or press buttons while it runs. Unrecognized reports are preserved
without interpretation. The output includes the controller identity and any
exposed serial number; inspect it before sharing.

`info` and `diagnose` automatically select a single matching interface. Multiple
matches require `--device PATH`, including multiple interfaces on one physical
controller. Access uses HIDAPI's shared mode, and no output reports or
configuration writes are sent. A device access error is reported with the
underlying HIDAPI error. If macOS denies access, review the privacy permissions
for the terminal/application running the command; availability must be confirmed
on actual hardware.

No arguments or `--help` prints help. Invalid arguments exit with code 2; device
or I/O failures exit with code 1. A successful command exits with code 0, including
`list` with no controller and `info` with an unavailable battery reading.

## Supported identities and limitations

The initial filter recognizes Microsoft (`045e`) Bluetooth/BLE gamepad identities
from SDL: One S (`02e0`, `02fd`, `0b20`), Series X|S (`0b13`), Elite Series 2
(`0b05`, `0b22`), and Adaptive (`0b0c`, `0b21`). Only `0b13` has been inspected on
connected hardware. Unknown IDs are excluded, and only
Generic Desktop joystick/gamepad usages are selected.

The HID device release number is displayed separately from firmware. It cannot
represent the full four-component Xbox firmware version. Firmware and LED fields
remain explicitly unavailable; the tool does not invent values or report a
remembered setting as a live reading.

This milestone includes no USB fallback, daemon, saved profiles, button mappings,
or controller configuration writes. Future light control should use off, on, and
integer brightness steps. A step range will be defined only after verifying an
interface on the target controller and firmware.

## Connected hardware findings

On the connected Series X|S controller (`045e:0b13`) over Bluetooth Low Energy:

- The 283-byte HID descriptor exposes gamepad input (report `01`) and rumble
  output (report `03`), with no feature reports or LED usages.
- Apple's GameController API detects the controller but returns `light = nil`.
- Installed SDL 3.4.16 opens the same HIDAPI interface and reports no RGB LED
  capability. This flag alone does not establish support for the white X light.
- macOS publishes firmware `5.9.2709.0` as `kBTFirmwareRevisionKey` on the HID
  registry service, outside HIDAPI's identity fields.

SDL's `SDL_JOYSTICK_HIDAPI_XBOX_ONE_HOME_LED` hint is real. In
[SDL 3.4.16's Xbox driver](https://github.com/libsdl-org/SDL/blob/release-3.4.16/src/joystick/hidapi/SDL_hidapi_xboxone.c),
it sends the GIP packet `0a 20 00 03 00 MODE BRIGHTNESS`. The callback also
registers for Bluetooth devices, but sends the same packet without Bluetooth
translation and ignores the send result. It provides no LED state query.
Accepting the hint is therefore not confirmation that the controller changed.

[xone](https://github.com/medusalix/xone#compatibility) supports LED control over
USB or the Xbox Wireless dongle and excludes Bluetooth. Its
[LED helper](https://github.com/medusalix/xone/blob/master/driver/common.c) reports
the mode stored by the driver, rather than querying the controller.
[xbledctl](https://github.com/Leclowndu93150/xbledctl#how-it-works) sends the same
LED command through Windows's XboxGIP driver interface and documents USB support.
Neither supplies a verified LED control or readback path for this macOS Bluetooth
connection.

## BLE GATT investigation

A separate read-only CoreBluetooth probe is available in
[tools/XboxGattProbe.java](tools/XboxGattProbe.java). It uses Java 25's native
interop API and macOS frameworks, with no additional Java libraries. Run it with
JBang and a Java 25 JDK selected by `mise`:

```sh
mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java

# Explicitly request the standard HID service instead of all exposed services.
mise exec java@corretto-25 -- jbang tools/XboxGattProbe.java hid
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

On this controller, it read firmware `5.9.2709.0` from `2A26` and battery value
`100` from `2A19`, independently of HIDAPI's report capture. This is the
controller's reported battery percentage, not a measurement of remaining runtime.
CoreBluetooth exposed device information (`180A`), battery (`180F`), and vendor
service `00000001-5F60-4C4F-9C83-A7953298D40D`.

The vendor service exposed two readable characteristics, with UUID prefixes
`00000002` (522 bytes) and `00000003` (32 bytes), and a write-only characteristic
with prefix `00000004`. All use the same UUID suffix as the service. Their
meaning remains unverified; none is established as LED control or LED readback.

CoreBluetooth omits HID service `1812`, even when explicitly requested. Raw ATT
discovery captured during fresh pairing confirms that the controller advertises
six primary services: `1800`, `1801`, `180A`, `180F`, `1812`, and the vendor
service. HID occupies handles `0016`-`0023`, with Report Map `2A4B` at `001C`.
Each of the three services exposed to the probe has no included services.

The raw Report Map read from the controller is 283 bytes, exactly matching the
macOS HID descriptor and the reference dumps from
[xpadneo](https://github.com/atar-axis/xpadneo/blob/master/docs/descriptors/xbxs.md)
and [ControllersInfo](https://github.com/DJm00n/ControllersInfo/blob/master/xboxone/xboxone_model_1914_bluetoothle_hid_report_descriptor.bin).
Its SHA-256 is `a681e2cb8b5d9466f258e2f07f0fcc518ec438231af1b61b125c213d2c62e165`.
It contains input report `01` and rumble output report `03`, with no feature
reports or LED usages. The HID Report Reference descriptors independently identify
input `01` (`01 01`) and output `03` (`03 02`). macOS filters the application's
service view, but did not remove LED fields from this descriptor. No LED interface
or readback was identified; the vendor service's meaning remains unknown.

Local evidence is in `target/xbox-hci-pairing-analysis.txt`, the extracted
`target/xbox-hci-pairing-report-map.bin`, and
`target/xbox-hci-report-map-comparison.txt`. The comparison does not require a
second computer or Bluetooth adapter.

Bluetooth traffic was also captured with Apple's
[PacketLogger](https://developer.apple.com/bluetooth/), included in Additional
Tools for Xcode. The download requires an Apple Account sign-in. Apple also
provides Bluetooth diagnostic profiles and instructions under
[Profiles and Logs](https://developer.apple.com/feedback-assistant/profiles-and-logs/?name=bluetooth&platform=macos).
A capture can expose ATT discovery responses below CoreBluetooth's application
view. The first command-line capture ran with administrator privileges while the
read-only GATT probe completed successfully. macOS logged successful authorization
for `com.apple.PacketLogger.HCI`, but the saved trace contained only PacketLogger's
final disconnect note, with no HCI/ATT traffic. The local files are
`target/xbox-hci-first.pkl` and its decoded `target/xbox-hci-first.txt`.
That initial attempt preceded installation of the Bluetooth logging profile and
does not provide a raw report-map comparison. An independent Linux/ESP32 scan has
not been performed.

The downloaded `Bluetooth_macOS.mobileconfig` enables Bluetooth debug logging,
private diagnostic data, HCI tracing, and firmware logging system-wide. Its
description specifies macOS 12 or newer and automatic removal after three days.
Apple's `Bluetooth_Logging_Instructions.pdf` requires installing the profile and
rebooting before gathering logs. On macOS Tahoe, review and install it under
System Settings > General > Device Management, and remove it there after the
capture. Installing it on this Mac enabled capture without a reboot; this observed
behavior does not replace Apple's documented setup instructions.
After the captures, the profile was removed and its generated
`com.apple.bluetooth.plist` logging override was confirmed absent. No PacketLogger
capture process remained running.

The subsequent capture, `target/xbox-hci-profile.pklg`, contains 436 Xbox ATT
packets, including 366 input notifications at handle `001E`. Reassembling the
read responses reproduces all seven characteristic values from the CoreBluetooth
probe byte for byte, including firmware, battery, and the 522-byte and 32-byte
vendor values. Included-service discovery also reached the controller, which
returned ATT `Attribute Not Found` for each of the three exposed service ranges.
The local summary is `target/xbox-hci-profile-analysis.txt`.

No primary-service discovery, characteristic discovery, or Report Map read appears
in this capture. This is consistent with macOS using its cached GATT database;
the trace cannot establish which additional services the controller exposes. No
LED command, LED state, or raw Report Map was obtained.

Capturing a controller power-off/reconnection also did not obtain a Report Map.
In `target/xbox-hci-reconnect.pklg`, macOS negotiates ATT MTU 23, reads identity
and battery values, and sends `01 00` writes to handles `001F` and `0015` before
the probe runs. These are consistent with enabling input and battery notifications;
they are not LED commands. No primary-service discovery is present, suggesting
the service database survived reconnection. Explicitly requesting `1812` again
returned zero services. The probe's seven characteristic values still match the
raw ATT reads byte for byte (`target/xbox-hci-reconnect-analysis.txt`).

Forgetting just the Xbox controller's Mac pairing and pairing it again triggered
the missing discovery and Report Map reads. The resulting trace is
`target/xbox-hci-pairing.pklg`. It establishes the six services and descriptor
comparison above; the probe's seven values also match that trace byte for byte.

Raw HCI captures include Bluetooth encryption key material as well as device
identifiers and unrelated Bluetooth traffic. Keep these capture files private.

For the local experiment, PacketLogger was copied to `target/PacketLogger.app`
and its code signature and command-line version were checked. This copy remains
available after reboot, independently of the mounted disk image. From the project
directory, start a capture, including buffered packets:

```sh
sudo ./target/PacketLogger.app/Contents/Resources/packetlogger convert -b -o target/xbox-hci-profile.pklg
```

Reconnect the controller and run the read-only GATT probe in another terminal.
Stop the capture with Ctrl+C, then decode it:

```sh
./target/PacketLogger.app/Contents/Resources/packetlogger convert -i target/xbox-hci-profile.pklg -o target/xbox-hci-profile.txt -f imtpnhdsfrl
```

Actual HCI/ATT traffic is needed to assess the report map; a PacketLogger note
alone does not supply that evidence.

For the GIP LED command, [xone documents](https://github.com/medusalix/xone#led-control)
temporary settings that last until disconnect.
[xbledctl documents](https://github.com/Leclowndu93150/xbledctl#why-does-it-run-in-the-background)
resetting to default after reconnection or power-off, with saved profiles on the
computer reapplied on connection. Its
[worker implementation](https://github.com/Leclowndu93150/xbledctl/blob/main/src/main.cpp#L355-L439)
sends a mode/brightness command and then closes the handle; this supports the
interpretation that the controller runs the effect after a command, rather than
requiring continuous LED updates from a host process. These are source-based
findings for those tools, not a persistence test on this controller. No persistent
LED-save command has been verified here.

## One-time LED experiment

By default, [examples/home_led_probe.rs](examples/home_led_probe.rs) sends one GIP
LED packet through HIDAPI to a single Series X|S Bluetooth gamepad (`045e:0b13`).
It requests brightness step 10, half SDL's normal step 20, rather than treating
the normal value as the maximum or using percentages as protocol values.

```sh
cargo run --locked --example home_led_probe
```

This is an experimental write, separate from the read-only inspection CLI. By
default it sends one command. It does not use the vendor GATT service or save a profile.
On the connected controller, macOS rejected the packet with
`IOHIDDeviceSetReport failed: (0xE00002F0) (iokit/common) data was not found`.
Delivery and a brightness change were not confirmed. If an interface accepts
the write, a visual check is still required because LED readback is unavailable.

## Bounded LED search

The same example can test 24 variants of the documented GIP LED command, keeping
the opcode, steady mode, and brightness step 10 fixed. It varies output versus
feature reports, sequence 0 versus 1, zero padding to 7/9/64 bytes, and HIDAPI's
optional leading zero for an unnumbered report. These are experimental transport
variants, not 24 documented Bluetooth LED commands.

```sh
# Preview all candidates without opening a device or sending reports.
cargo run --locked --example home_led_probe -- --sweep --dry-run

# Run the bounded search with exactly one matching Bluetooth gamepad connected.
cargo run --locked --example home_led_probe -- --sweep
```

Before the search, it sends SDL's nine-byte Bluetooth rumble report with all motor
strengths zero as an output-access control. It stops at the first accepted LED
candidate for visual inspection, an incomplete write, or any error other than
macOS's missing-report rejection. Acceptance is not proof of a brightness change.
If every candidate is rejected, it exits with code 1. No arbitrary report IDs,
vendor GATT opcodes, or unknown configuration payloads are written.

On the connected `045e:0b13` controller with firmware `5.9.2709.0`, the rumble
control was accepted, and all 24 LED candidates were rejected with
`IOHIDDeviceSetReport failed: (0xE00002F0) (iokit/common) data was not found`.
The output path works for the declared rumble report but does not expose a route
for the tested LED packets. This is not an exhaustive search of the controller's
firmware or vendor GATT protocol. The raw local log is `target/led-sweep.txt`.

In a [May 2025 xpadneo discussion](https://github.com/atar-axis/xpadneo/issues/18#issuecomment-2888891363),
a maintainer reports that Microsoft removed vendor extensions from the Bluetooth
HID descriptors. Earlier speculation in that discussion concerned Elite Series 2
extensions, not a verified LED command for this Series X|S controller. This is
consistent with the observed descriptor and rejections; it does not establish
the meaning of the separate vendor GATT service.

## Verification

```sh
cargo test --locked --all-targets
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo build --release --locked
```

The tests cover CLI validation, device filtering/selection, recognized,
truncated, and unknown battery reports, and the LED candidates' opcode and
brightness. They use synthetic inputs and do not prove that the controller
exposes those reports on macOS.

When the controller is available:

1. Pair it over Bluetooth and run `list`; confirm the identity and transport.
2. Run `info`; compare any reported battery level with macOS, allowing for its
   coarse resolution. Record the independently reported firmware version.
3. Run `diagnose` and generate input; confirm the descriptor and report bytes are
   captured, and inspect unknown report IDs before assigning them meanings.
4. Confirm a game can still receive input while the CLI uses shared access.
5. Check selection with multiple controllers and errors after disconnecting one.

## Protocol references

- [HIDAPI Rust wrapper](https://github.com/ruabmbua/hidapi-rs), including its
  `macos-shared-device` feature and report descriptor API.
- [SDL Xbox One HIDAPI driver](https://github.com/libsdl-org/SDL/blob/main/src/joystick/hidapi/SDL_hidapi_xboxone.c),
  especially its Bluetooth battery packet/state handlers. The battery
  interpretation follows that implementation, without presenting its approximate
  percentage mapping as a precise measurement.
- [SDL controller identities](https://github.com/libsdl-org/SDL/blob/main/src/joystick/usb_ids.h).
- [GIP protocol notes](https://gist.github.com/TheNathannator/c5d3b41a12db739b7ffc3d8d1a87c60a),
  which document the LED command for the GIP transport.
- [xbledctl](https://github.com/Leclowndu93150/xbledctl), an existing Windows USB
  LED tool whose documented support excludes Bluetooth.

## Licence

Licensed under the [Mozilla Public License 2.0](LICENSE).
