# Bluetooth experiments with the XIAO nRF52840

[Analysis](analysis.md) | [macOS Bluetooth findings](bluetooth-macos.md)

The XIAO runs its own Bluetooth central and exchanges commands with the Mac over
USB CDC serial. Nordic's SoftDevice sends GATT requests directly to the
controller. Apple's HID report mapping and CoreBluetooth service restrictions
are outside this path. This provides a transport for experiments; a Bluetooth
brightness command remains unverified until a repeatable visual change is observed.

The initial trials used `5.9.2709.0`. The
[post-update campaign](brightness-campaign.md#post-update-results-52360)
confirmed `5.23.6.0` over GATT and repeated seven LED formats and HID
Suspend/Resume. All left the light unchanged; USB brightness still worked.

## Setup

Run commands from the repository root. Install Arduino CLI with Homebrew:

```sh
brew install arduino-cli
```

Java tools require Java 25 and JBang. The Seeed board package also invokes
`python`. Provide a local alias to the installed Python 3 interpreter:

```sh
mkdir -p target/xiao/bin
ln -sf "$(command -v python3)" target/xiao/bin/python
export PATH="$PWD/target/xiao/bin:$PATH"
```

Install the pinned non-mbed board package. The checked-in configuration keeps
board packages, downloaded archives and Arduino libraries under ignored `target/`:

```sh
arduino-cli core update-index --config-file tools/xiao/arduino-cli.yaml
arduino-cli core install Seeeduino:nrf52@1.1.13 --config-file tools/xiao/arduino-cli.yaml
arduino-cli board list --config-file tools/xiao/arduino-cli.yaml
```

[Seeed recommends its non-mbed package for Bluetooth](https://wiki.seeedstudio.com/XIAO_BLE/).
The connected board initially enumerated as `2886:8045`,
`Seeed XIAO nRF52840 Sense`, at `/dev/cu.usbmodem1101`.
Use the board and port reported on your machine; the port can change after upload.
The regular XIAO uses board ID `xiaonRF52840`; the Sense uses `xiaonRF52840Sense`.
The probe uses neither the Sense microphone nor its motion sensor.

## Compile and upload

```sh
arduino-cli compile --config-file tools/xiao/arduino-cli.yaml \
  --fqbn Seeeduino:nrf52:xiaonRF52840Sense \
  --build-path "$PWD/target/xiao/build" tools/xiao/XboxBleProbe

arduino-cli upload --config-file tools/xiao/arduino-cli.yaml \
  --fqbn Seeeduino:nrf52:xiaonRF52840Sense \
  --port /dev/cu.usbmodem1101 --input-dir target/xiao/build

arduino-cli board list --config-file tools/xiao/arduino-cli.yaml
```

Upload replaces the XIAO application through its existing bootloader. It does
not update Xbox firmware. If automatic bootloader entry fails, use a native
prompt before manually pressing the XIAO reset button twice rapidly:

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/NativeMacDialog.java 'Enter XIAO bootloader' \
  'Press the XIAO reset button twice rapidly. Click OK when the bootloader appears.'
```

Repeat board enumeration and upload using the bootloader's port. Do not erase
the chip or replace the bootloader. Seeed's bundled macOS uploader and compiler
are Intel executables and require Rosetta on Apple Silicon.

## Guided experiment

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --self-test

JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --inspect /dev/cu.usbmodem1101

JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --run /dev/cu.usbmodem1101
```

`--inspect` pairs, reads identity and firmware, discovers services,
characteristics and report-reference descriptors, and reads the HID Report Map.
It sends no characteristic writes. Pairing stores Bluetooth bond information.
`--run` also subscribes to HID input, sends zero-strength rumble, and runs the
bounded LED sequences below. Pairing to the XIAO may require pairing back to
the Mac afterwards. Controller USB must be disconnected during Bluetooth tests.
If the controller has reconnected to macOS, disconnect it in System Settings >
Bluetooth. Keep the Mac's Bluetooth on. Hold the small Pair button beside the
controller's USB port for three seconds, until its Xbox light flashes rapidly;
then acknowledge the runner's pairing prompt. Microsoft documents the
[three-second Pair-button hold](https://www.microsoft.com/en-us/windows/learning-center/how-to-connect-xbox-controller-to-pc).

Java calls AppKit `NSAlert.runModal` through its Foreign Function and Memory API.
JBang supplies `-XstartOnFirstThread`. Physical-action prompts have OK and Cancel
buttons and no timeout. Observation prompts have Changed, Unchanged and Cancel
buttons. Cancel stops further tests and requests disconnection. An AFK pause does
not count as consent or a visual observation. Each test checks the encrypted
connection after its readiness prompt; reconnects revalidate the characteristic
schema and controller serial number before using previously discovered handles.
Instructions use numbered steps separated by blank lines. Between prompts, a
native progress window shows scanning, connection, discovery and individual
write results. The runner processes AppKit events while waiting for serial
responses and between writes, so the window remains responsive. Acknowledged
instruction windows close before the next operation begins.

Each candidate alternates steps 0 and 20 twice, with three seconds between writes:

| Candidate                | Destination                      | Payload                                    |
| ------------------------ | -------------------------------- | ------------------------------------------ |
| HID seven-byte GIP       | Discovered output report `03/02` | `0a 20 SS 03 00 01 BB`                     |
| HID thirteen-byte GIP    | Same HID output characteristic   | Seven-byte packet followed by six zeroes   |
| Vendor seven-byte GIP    | Vendor characteristic `00000004` | `0a 20 SS 03 00 01 BB`                     |

`SS` runs from `01` to `04`; `BB` alternates `00`, `14`, `00`, `14`.
The HID Report Reference is report ID `03`, report type `02` (output).
GATT writes contain characteristic data directly; the runner adds no macOS HID
report selector. A seven-byte GIP packet is deliberately tested as an experimental
characteristic value, rather than treated as a declared Bluetooth LED report.
HID candidates may cause rumble. The runner sends `0f 00 00 00 00 00 00 00`
to the rumble characteristic after each sequence.

A controller ATT response of `0000` establishes write acceptance. A visual
observation establishes whether the light changed. When the user reports a
change, the runner reconnects and repeats that candidate. Failed writes and
unchanged light are recorded separately. No undocumented command sweep or
Xbox firmware update is performed.

Transcripts are created as mode `0600` under `target/xiao/runs/`. They include
timestamps, device identifiers, discovered handles, payloads, SoftDevice errors,
ATT responses and explicit observations. Pairing keys are not exported.
The board logs only the first eight notifications per connection and exposes
the cumulative count through `STATUS`, keeping AFK logs bounded.

## Serial command reference

The firmware accepts ASCII lines at 115200 baud. Arguments use lowercase hex,
except decimal address type. Handles and offsets use four hex digits. Send one
request at a time and wait for its response. A successful SoftDevice submission
does not emit a success line; completion arrives in the GATT response.

The Java runner retries `ERROR SOFTDEVICE 00000011` (busy) only for
`SERVICES`, `CHARS`, `DESCS` and `READ`, with at most ten retries 100 ms apart.
Writes, other errors and timeouts stop the request. A busy submission has not
queued the requested operation.

Use the Java runner for an individual command:

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --command /dev/cu.usbmodem1101 PING

JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --command /dev/cu.usbmodem1101 STATUS
```

For interactive discovery, use Arduino CLI's serial monitor. Close other serial
clients first:

```sh
arduino-cli monitor --config-file tools/xiao/arduino-cli.yaml \
  --port /dev/cu.usbmodem1101 --config baudrate=115200
```

| Command                     | Response or operation                                                          |
| --------------------------- | ------------------------------------------------------------------------------ |
| `PING`                      | `PONG XboxBleProbe 1`                                                          |
| `STATUS`                    | `STATUS connected secured notification-count`                                  |
| `SCAN`                      | `OK SCAN`; subsequent `ADV address type rssi name` for Xbox names              |
| `STOP`                      | Stop scanning; `OK STOP`                                                       |
| `FORGET address 0`          | Remove only this public peer's saved XIAO bond while disconnected; `OK FORGET` |
| `CONNECT address type`      | Connect to an address returned by `ADV`; `CONNECTED connection-handle`         |
| `PAIR`                      | Request pairing/encryption; `OK PAIR`, then `AUTH` and/or `SECURITY`           |
| `SERVICES start`            | `SERVICE start end uuid`, then `DONE SERVICES status`                          |
| `CHARS start end`           | `CHAR declaration value properties uuid`, then `DONE CHARS status`             |
| `DESCS start end`           | `DESC handle uuid`, then `DONE DESCS status`                                   |
| `READ handle offset`        | `READ status handle offset hex-data`                                           |
| `WRITE handle hex-data`     | ATT Write Request; `WRITE status handle`                                       |
| `WRITE_CMD handle hex-data` | ATT Write Command; `SENT handle count` after local transmission                |
| `DISCONNECT`                | `DISCONNECTED connection-handle reason`                                        |

Addresses are six bytes in Nordic's byte order, exactly as returned by `ADV`.
Vendor UUIDs are printed as 32 canonical-order hex digits without hyphens.
Standard UUIDs use four hex digits. Characteristic properties use the Bluetooth
GATT property bitmask. Discovery responses may contain only part of a range;
continue after the last returned service, characteristic value or descriptor.
`010a` means Attribute Not Found and ends discovery. Reads use the default MTU 23:
append 22-byte chunks using increasing offsets until a shorter response;
`0107` after a full chunk means Invalid Offset and ends an exact-length value.
`ERROR SOFTDEVICE code` means local submission failed. Nonzero ATT response
status means the controller rejected the operation. Disconnects and timeouts
stop the guided runner instead of allowing a response to match a later request.

### HID Suspend/Resume experiment

`--suspend-test` tests a separate power-management path on the installed
firmware. It discovers HID Control Point `2A4C` and checks its Write Without
Response property. It sends standard Suspend `00`, waits five seconds while
checking the encrypted connection, and sends Exit Suspend `01`. Resume is also
attempted when status collection fails. A native observation prompt records
whether the light went out and returned; a second prompt checks input after
resume. This can establish an on/off workaround, not adjustable intensity.

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/XboxBleExperiment.java --suspend-test /dev/cu.usbmodem1101
```

This mode requires the updated XIAO sketch with `WRITE_CMD` support. Its
`SENT` response comes from Nordic's local Write Command transmission-complete
event. The remote controller provides no ATT acknowledgement for this operation;
`SENT` must not be reported as controller acceptance. No LED control is assumed
from a local transmission event alone.

On reconnection, Bluefruit starts encryption automatically when it finds a
saved bond. `PAIR` waits for that existing operation instead of requesting a
second authentication procedure. The first Suspend/Resume attempt stopped at
`ERROR PAIR`; a second connected but disconnected with reason `3e` before
encryption completed. Neither sent a control-point write.

The guided prompt explicitly requests fresh pairing. For the selected public
Xbox address, the runner now sends `FORGET address 0` before connecting. This
removes only that peer's saved key on the XIAO so stale bond encryption cannot
precede fresh authentication. The operation requires a disconnected board and
a public address; it does not alter macOS pairing records or other XIAO bonds.

The fresh-pairing retry succeeded on firmware `5.9.2709.0`. Authentication
completed with `AUTH 00` and `SECURITY 1 2 16`. The runner transmitted
`WRITE_CMD 001a 00`, waited five seconds, and transmitted `WRITE_CMD 001a 01`.
Both returned `SENT 001a 1`; the connection stayed encrypted. The recorded
visual observation was `Unchanged`. After resume and manual controller input,
the notification count rose from 50 to 159. The runner then received
`DISCONNECTED 0 16`. This establishes no Suspend/Resume light-control workaround.

## Verification

The firmware is built against Seeed nRF52 1.1.13, Bluefruit 0.21.0 and TinyUSB 1.7.0.
Java self-checks cover LED framing, padding, sequence validation and ATT error
handling. Hardware results are recorded separately from compilation and self-checks.

On 2026-10-24, Homebrew Arduino CLI 1.5.1 compiled the probe for the detected
Sense board: 117248 bytes of flash and 14600 bytes of static RAM. Upload through
the existing bootloader succeeded. The Java runner sent `PING` and received
`PONG XboxBleProbe 1` over `/dev/cu.usbmodem1101`. The guided runner then entered
its native pairing prompt.

The subsequent run paired successfully with installed firmware `5.9.2709.0`.
Security reached mode 1, level 2, with a 16-byte encryption key. Discovery found
the same six primary services and HID report references as the earlier Mac
capture. Input notifications arrived at `001e`, and zero-strength rumble writes
to `0022` succeeded.

| Candidate               | Value handle | Controller ATT response | Visual observation |
| ----------------------- | ------------ | ----------------------- | ------------------ |
| Seven-byte HID GIP      | `0022`       | 4/4 rejected, `0180`    | Unchanged          |
| Thirteen-byte HID GIP   | `0022`       | 4/4 accepted, `0000`    | Unchanged          |
| Seven-byte vendor GIP   | `002a`       | 4/4 accepted, `0000`    | Unchanged          |

These observations were entered through the runner's buttons; the program has
no optical brightness sensor. Status `0180` is an application-specific ATT
error; its precise meaning is not established here. The accepted writes prove
delivery through the XIAO without Apple's HID mapping. Their unchanged visual
results provide no working Bluetooth brightness command. The runner requested
disconnection at the end and received `DISCONNECTED 0 16`.

The first runner left its UI thread waiting between prompts, which caused a
busy cursor and stale dialog display. The updated runner explicitly hides each
completed prompt and processes native events during waits. Its UI self-test
updates a progress window and closes it without requiring controller actions:

```sh
JBANG_DIR="$PWD/target/jbang" mise exec java@corretto-25 -- \
  jbang tools/NativeMacDialog.java --self-test
```

Protocol references: [Seeed board definitions](https://github.com/Seeed-Studio/Adafruit_nRF52_Arduino/blob/master/boards.txt),
[Nordic GATT client API](https://docs.nordicsemi.com/bundle/s140_api/page/group___b_l_e___g_a_t_t_c___f_u_n_c_t_i_o_n_s.html),
and [Apple NSAlert](https://developer.apple.com/documentation/appkit/nsalert).
