# xbox-light

Tools to inspect an Xbox Wireless Controller on macOS and control its
central Xbox button LED over USB. The Java utility provides USB LED control;
the Rust CLI provides Bluetooth identity, battery and report inspection.

Current status: USB LED control works. Bluetooth LED control does not work.
The LED setting does not survive unplugging USB, confirmed by the user.
Firmware and LED readback are unavailable through the implemented interfaces.

## Analysis

[ANALYSIS.md](ANALYSIS.md) summarises the current results and evidence boundaries.

- [Firmware reverse engineering](FIRMWARE.md): official firmware download,
  version matching, Ghidra analysis, GIP LED-to-PWM path and Bluetooth callbacks.
- [Bluetooth on macOS](BLUETOOTH-MACOS.md): HID/GATT discovery, unsuccessful
  LED attempts, HCI captures, descriptor comparisons and remaining leads.
- [USB](USB.md): working LED utility, Apple's 13-byte framing, unplug behaviour
  and the unsuccessful firmware-readback attempt.

The controller reports `5.9.2709.0`. Firmware analysis used downloaded MERL
`5.23.6.0`; the exact installed image was not found. No firmware was updated.

## USB LED control on macOS

[XboxUsbLed.java](tools/XboxUsbLed.java) controls the connected USB `045e:0b12`
controller through shared HID access, with no administrator authentication or
driver detachment. It automatically detects USB or Bluetooth and routes them
separately. Requires Java 25 and JBang. Intensity values are 0-47.

```sh
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --help
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --inspect
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --on
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --brightness 10
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --increment
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --decrement 5
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --off
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --glow
```

`-h`, `--help`, and no arguments show help without opening a controller. `--on`
sets step 20; `--off` sets zero. `--brightness N` accepts absolute integer steps
0-47; a bare number remains supported. Increment and decrement default to one
step and accept an optional amount from 1 to 47. Invalid values and changes
outside 0-47 are rejected with exit status 2 and a short error.

Relative changes use the last brightness this tool set, cached in
`~/Library/Caches/xbox-light/usb-led.state`. After a USB reconnect or Mac restart,
set an absolute value first. The tool cannot read the current brightness or
detect changes made by other software. `--inspect` detects USB `045e:0b12` or
Bluetooth `045e:0b13` without sending a command. LED commands on Bluetooth return
exit status 1 with an explicit unsupported message, without entering the USB
write path or changing its cache. No Bluetooth LED command has been verified
for this controller; macOS rejected both the seven-byte GIP packet and working
13-byte USB framing during the Bluetooth test.

`--glow` breathes between steps 0 and 20 for 30 seconds, then switches the light
off over USB. Steady settings remain until another command or controller
reinitialisation. The user confirmed brightness was lost after unplugging USB.
The [USB analysis](USB.md) explains Apple's required 13-byte output buffer.

## Build and install the Rust CLI

Requires macOS, Rust 1.85 or newer, and Apple's Command Line Tools. Install them
with `xcode-select --install` if they are missing. HIDAPI's macOS implementation
is compiled into the binary; no separate HIDAPI installation is needed.

```sh
cargo build --release --locked
./target/release/xbox-light --help

# Optional: install into Cargo's bin directory.
cargo install --path . --locked
```

## Bluetooth inspection usage

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

## Rust CLI identities and limitations

The initial filter recognizes Microsoft (`045e`) Bluetooth/BLE gamepad identities
from SDL: One S (`02e0`, `02fd`, `0b20`), Series X|S (`0b13`), Elite Series 2
(`0b05`, `0b22`), and Adaptive (`0b0c`, `0b21`). Only `0b13` has been inspected on
connected hardware. Unknown IDs are excluded, and only
Generic Desktop joystick/gamepad usages are selected.

The HID device release number is displayed separately from firmware. It cannot
represent the full four-component Xbox firmware version. Firmware and LED fields
remain explicitly unavailable; the tool does not invent values or report a
remembered setting as a live reading.

The Rust CLI only inspects Bluetooth devices. USB LED control is provided
by the Java utility described above. Read-only GATT discovery commands
and capture procedures are documented in [BLUETOOTH-MACOS.md](BLUETOOTH-MACOS.md).

## Licence

Licensed under the [Mozilla Public License 2.0](LICENSE).
