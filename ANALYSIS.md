# Xbox controller analysis

Updated: 2026-10-15. Target: Microsoft Xbox Series X|S controller, USB
`045e:0b12` and Bluetooth Low Energy `045e:0b13`, on macOS.

Current status: USB LED control works. Bluetooth LED control does not work.
The LED setting does not survive unplugging USB, confirmed by the user.

## Firmware reverse engineering

[FIRMWARE.md](FIRMWARE.md) covers the official download, hardware-family
match, exact-version search, startup decompression, GIP LED-to-PWM path,
saved configuration, Bluetooth callbacks and repeatable Ghidra analysis.

The analysed Microsoft MERL application is `5.23.6.0`. The controller
reports `5.9.2709.0`; its exact image was not found. Firmware readback
did not succeed, and no firmware was updated.

## Bluetooth on macOS

[BLUETOOTH-MACOS.md](BLUETOOTH-MACOS.md) covers HID and CoreBluetooth
discovery, packet attempts, HCI/ATT captures, descriptor comparisons,
firmware comparison, tools, source links and remaining unknowns.

No brightness or glow command worked. macOS rejected all 24 original
HID candidates and four later LED packet attempts, while accepting the
zero-strength rumble control. Private CoreBluetooth discovery and a direct
HID Report Map read were also rejected. Four vendor GATT writes were
acknowledged, with no visible LED change confirmed by the user.
No Bluetooth LED readback is available.
These results do not establish that every possible interface is absent.

The built-in radio investigation traced HID report mapping to `BTLEServer`.
Replacing the upper HID driver would preserve that mapping. Locally claiming
its restricted-service entitlement prevented the test executable from launching.
No driver or system configuration was installed or changed.

## USB

[USB.md](USB.md) covers the working Java CLI, macOS driver framing,
brightness steps, glow, host-side cache, unplug behaviour, raw USB
tests and the unsuccessful firmware-readback attempt.

Shared HID control works without administrator authentication or driver
detachment. Apple's output handler requires a 13-byte buffer. The app
automatically detects USB or Bluetooth and dispatches them separately.
Bluetooth LED requests report unsupported. The user confirmed USB
brightness step 5 still works after that change.

Raw evidence, binaries, captures and generated analysis remain under
ignored `target/`. Bluetooth captures contain pairing/encryption material
and were restricted to mode `0600`.
