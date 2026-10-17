# xbox-light

macOS tools for Xbox Wireless Controller inspection and USB home-button LED control.

| Capability                  | USB `045e:0b12`             | Bluetooth LE `045e:0b13`       |
| --------------------------- | --------------------------- | ------------------------------ |
| LED off, brightness, glow   | Verified on hardware        | ⚠️ No working command found    |
| Identity                    | Java transport inspection   | Rust CLI and Java GATT probe   |
| Battery                     | No implemented reading      | Coarse HID level; GATT percent |
| Firmware version            | No implemented reading      | GATT and IORegistry            |
| Live LED state readback     | Unavailable                 | Unavailable                    |
| Brightness after disconnect | Setting lost                | No verified setting            |

Hardware results: Xbox Series X|S, firmware `5.9.2709.0`, macOS `26.7.1`.
Only Bluetooth product ID `0b13` was tested on hardware.

> [!IMPORTANT]
> USB brightness must be reapplied after reconnecting. Relative adjustments use
> the last value set by this tool; the controller does not provide brightness readback.

## USB LED control

Requires macOS, Java 25 and JBang. Shared HID access keeps Apple's driver attached
and requires no administrator authentication.

```sh
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --inspect
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --brightness 10
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --off
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --glow
```

| Argument          | Behaviour                                                |
| ----------------- | -------------------------------------------------------- |
| `--inspect`       | Detect USB or Bluetooth without writing                  |
| `--on`            | Set brightness step 20                                   |
| `--off`           | Set brightness zero                                      |
| `--brightness N`  | Set an integer step from 0 to 47; bare `N` also accepted |
| `--increment [N]` | Increase cached value by 1, or by `N` from 1 to 47       |
| `--decrement [N]` | Decrease cached value by 1, or by `N` from 1 to 47       |
| `--glow`          | Breathe between 0 and 20 for 30 seconds, then switch off |
| `-h`, `--help`    | Show help; no arguments also show help                   |

Out-of-range values and relative results are rejected. After reconnecting USB
or restarting the Mac, set an absolute value before using relative adjustments.
Cache: `~/Library/Caches/xbox-light/usb-led.state`. Changes made by other software
cannot be detected. Bluetooth LED requests return an unsupported error.

See [USB reference](docs/usb.md) for framing, state handling and hardware results.

## Bluetooth inspection

Requires macOS, Rust 1.85+ and Apple's Command Line Tools
(`xcode-select --install`). HIDAPI is compiled into the binary.

```sh
cargo build --release --locked
./target/release/xbox-light --help
cargo install --path . --locked
```

1. Pair and connect the controller in macOS Bluetooth settings.
2. Run `xbox-light list` to enumerate matching interfaces.
3. Run `xbox-light info` for identity and a coarse battery reading.
4. Run `xbox-light diagnose` while pressing buttons or moving sticks.

```sh
xbox-light list
xbox-light info
xbox-light diagnose > controller-diagnostics.txt

# Select a path from list when multiple interfaces match.
xbox-light info --device 'DevSrvsID:123456'
xbox-light diagnose --device 'DevSrvsID:123456' > controller-diagnostics.txt
```

| Command    | Output and limits                                                                  |
| ---------- | ---------------------------------------------------------------------------------- |
| `list`     | Name, IDs, transport, path, serial if exposed, HID usage and device release number |
| `info`     | Identity; up to 3 seconds waiting for battery report `04 flags`                    |
| `diagnose` | Raw HID descriptor; timestamp, length and hex bytes for 5 seconds of input reports |

The CLI sends no output reports or configuration writes. It does not pair or
reconnect controllers. Device paths change across connections; multiple matching
interfaces require `--device PATH`, including interfaces on one physical controller.

Battery levels are low, medium, high or full (0-3). USB power is reported separately
without inferring charging or battery level. A missing battery report means
unavailable. The device release number is separate from the full firmware version;
firmware and LED fields remain unavailable in the Rust CLI.

> [!CAUTION]
> Diagnostic output can contain serial numbers and opaque device data. Bluetooth
> captures can also contain encryption keys. Review output before sharing it.

<details>
<summary>Recognised identities and exit codes</summary>

Microsoft vendor `045e`; Generic Desktop joystick/gamepad usages only.
USB interfaces, unrelated Microsoft devices and unknown IDs are excluded.

| Controller     | Bluetooth/BLE product IDs |
| -------------- | ------------------------- |
| One S          | `02e0`, `02fd`, `0b20`    |
| Series X\|S    | `0b13`                    |
| Elite Series 2 | `0b05`, `0b22`            |
| Adaptive       | `0b0c`, `0b21`            |

| Exit | Meaning                                                              |
| ---- | -------------------------------------------------------------------- |
| `0`  | Success, help, empty list or unavailable battery reading             |
| `1`  | Device/I/O failure; Java LED requests over Bluetooth are unsupported |
| `2`  | Invalid arguments                                                    |

Device access errors include the underlying HIDAPI error. If macOS denies access,
review privacy permissions for the terminal or application running the command.

</details>

## Technical documentation

| Document                                      | Contents                                                     |
| --------------------------------------------- | ------------------------------------------------------------ |
| [Analysis](docs/analysis.md)                  | Results, evidence boundaries and repository layout           |
| [USB](docs/usb.md)                            | LED utility, 13-byte macOS framing, retention and readback   |
| [Bluetooth on macOS](docs/bluetooth-macos.md) | GATT schema, packet tests, capture results and access limits |
| [Firmware](docs/firmware.md)                  | Pinned image, recovered dispatch tables, PWM path and checks |

## Licence

[Mozilla Public License 2.0](LICENSE).
