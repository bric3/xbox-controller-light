# USB

Updated: 2026-10-15. Target: Microsoft Series X|S `045e:0b12` connected to
this Mac by USB-C. The controller reports firmware `5.9.2709.0` over Bluetooth.

Current status: USB LED control works. Off, steady brightness and simulated
glow have been confirmed on the controller. The LED setting does not survive
unplugging USB, confirmed by the user. No firmware was updated or dumped.

The user also confirmed that `--brightness 5` succeeds after automatic
USB/Bluetooth detection was added. The working USB packet and animation
implementation was retained; Bluetooth requests use a separate handler.

[Analysis index](ANALYSIS.md) | [Firmware reverse engineering](FIRMWARE.md) | [Bluetooth on macOS](BLUETOOTH-MACOS.md) | [USB](USB.md)

## Connected device

- Microsoft `045e:0b12`, USB product name `Controller`, full speed (12 Mbit/s).
- Device release `0x0509`. This field does not establish the full firmware version.
- Apple's `XboxUSBDevice` owns the USB device; `XboxSeriesXGamepad` owns interface 0.
- Interface 0 uses vendor class/subclass/protocol `ff/47/d0`, with 64-byte
  interrupt endpoints `02` OUT and `82` IN. The configuration also contains
  alternate settings for audio and bulk endpoints; no USB DFU interface is declared.

The earlier Bluetooth firmware reading `5.9.2709.0` is recorded in
[BLUETOOTH-MACOS.md](BLUETOOTH-MACOS.md). The full firmware version was not
read over USB.

## LED utility

[XboxUsbLed.java](tools/XboxUsbLed.java) uses Java 25 FFM with the installed
IOKit and CoreFoundation frameworks. It detects exactly one USB `045e:0b12` or
Bluetooth `045e:0b13` controller through shared HID access. The USB path sends
the LED command with the required 13-byte framing. It accepts intensity steps
0-47. It leaves the system driver attached and does not send firmware, pairing
or calibration commands.

```sh
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --self-test
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --help
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --inspect
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --on
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --brightness 10
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --increment
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --decrement 5
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --off

# Thirty seconds of breathing, ending with the light off.
mise exec java@corretto-25 -- jbang tools/XboxUsbLed.java --glow
```

Steady commands return immediately. `--glow` varies intensity from 0 to 20 with
a three-second cosine cycle and a 50 ms pause between writes. It uses steady/off
commands and incrementing non-zero sequences, then sends off on completion.
This is a host-generated animation, without depending on undocumented firmware
animation modes. `--on` sets step 20, `--off` sets zero, and `--brightness N`
selects an absolute integer from 0 to 47. Bare numeric arguments still work.
`--increment [N]` and `--decrement [N]` change the last value this tool set by
one step unless an amount from 1 to 47 is supplied. Results outside 0-47 are
rejected, with no clamping or LED write. Help and invalid arguments are handled
before loading the native frameworks; usage errors return status 2 without a
stack trace. `-h`, `--help`, and no arguments return help with status zero.

The controller provides no verified brightness readback. Relative commands use
`~/Library/Caches/xbox-light/usb-led.state`, containing the last accepted value,
the live HID registry entry ID and the macOS boot-session UUID. Reconnects and
restarts require a new absolute setting. A file lock serialises commands from
this utility; atomic replacement stores completed settings. The cached baseline
is invalidated before writes, so an interrupted command requires a new absolute
value. Settings made by other software cannot be detected. This is local tool
state, with no persistent setting written to controller firmware.

Bluetooth detection uses a separate handler and supplies no verified LED command
for this controller. LED requests on Bluetooth exit with status 1, without USB
writes or cache changes. `--inspect` detects either transport without writing.
Device access from the agent sandbox requires an execution
exception; ordinary Terminal use does not require `sudo`.

## Brightness retention

The LED setting does not survive unplugging USB. The user confirmed the
brightness was lost after unplugging and switching to Bluetooth. A new
absolute setting must be applied after reconnecting.

The traced firmware command changes runtime state, with no persistent-save
command identified. The Mac cache only remembers this tool's last accepted
value for relative adjustments; it does not save brightness in the controller.
`--on` uses host-selected step 20; the exact factory brightness remains unknown.
[Firmware defaults and saved configuration](FIRMWARE.md#defaults-and-saved-configuration)
records the separate EEPROM-backed timing values.

[xone documents](https://github.com/medusalix/xone#led-control) settings that last
until disconnect. [xbledctl documents](https://github.com/Leclowndu93150/xbledctl#why-does-it-run-in-the-background)
resetting after reconnect or power-off and reapplying profiles saved on the host.
Its [worker](https://github.com/Leclowndu93150/xbledctl/blob/main/src/main.cpp#L355-L439)
sends a mode/brightness command and closes the handle. Those source findings
agree with the observed unplug behaviour; they do not provide a persistent-save
command. Our simulated glow continuously sends brightness changes while running.

Unplugging with batteries fitted can leave the controller powered. Full power
removal was not tested separately, and no brightness readback is available.

## macOS HID framing

The live registry's `IOUserClasses` shows this inheritance chain:

```text
XboxSeriesXGamepad -> XboxWirelessGamepad -> XboxHIDDevice
```

In the installed ARM64 driver, `XboxWirelessGamepad::setReport` at
`0x10000dc44` accepts output reports and checks the low byte of the report ID:
ID `1` requires exactly 13 bytes; ID `5` requires exactly five bytes. The length
checks are at `0x10000deb4` through `0x10000dee0`. Accepted buffers reach
`XboxHIDDevice::setReport` at `0x100009dc8`, which forwards the memory descriptor
to `IOUSBHostPipe::AsyncIO` without rebuilding the GIP header.

The working LED-off buffer is:

```text
HID report type: output
HID metadata report ID: 1
Buffer length: 13
Buffer: 0a 20 01 03 00 00 00 00 00 00 00 00 00
```

The GIP opcode remains `0x0a` and its declared payload remains three bytes.
The HID metadata report ID and GIP opcode have different roles. The six trailing
zero bytes satisfy Apple's inherited length check. The user confirmed that this
command turned the Xbox button off. The registry entry ID stayed `0x1000839ce`
before and after the write, and its input report count increased from 12 to 15.
The final shared HID breathing run completed 532 updates followed by off. Its
output counter increased from 2 to 535 and its input counter from 15 to 564,
with the same driver entry remaining attached.

The earlier seven-byte writes using HID metadata IDs `0x0a` and `1` returned
`0xe00002eb` (`kIOReturnAborted`). An asynchronous write reached its callback
and returned the same error. The padded synchronous write succeeded.
The earlier assumption that the Series class bypassed the wireless handler
was incorrect; the live inheritance chain supplied the missing link.

Evidence:

- [Driver disassembly](target/xbox-driver-arm64-disassembly.txt).
- [Seven-byte synchronous failure](target/firmware-analysis/usb-led-step-10-hid-1.txt), [asynchronous failure](target/firmware-analysis/usb-led-step-10-async.txt).
- [Successful padded LED-off write](target/firmware-analysis/usb-hid-led-off-padded.txt), [shared HID breathing run](target/firmware-analysis/usb-hid-led-glow.txt).
- [Registry before the padded write](target/firmware-analysis/usb-registry-after-glow.txt), [after the padded write](target/firmware-analysis/usb-registry-after-padded-hid.txt), [after shared HID breathing](target/firmware-analysis/usb-registry-after-shared-glow.txt).
- [User's visual confirmations](target/firmware-analysis/visual-confirmation.txt).

## Other projects and the leads they supplied

| Project                                                                                                                                                      | Relevant implementation                                                                                                        | What was applied here                                                                                                          |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| [xbledctl](https://github.com/Leclowndu93150/xbledctl/blob/60d47686db18e9dc6ef9378c30c34f5a1084f195/docs/RESEARCH.md)                                        | Reports working USB LED control on `045e:0b12`, firmware `5.23.6.0`; Windows `XboxGIP` interface adds a 20-byte routing header | Distinguished driver framing from the seven-byte wire command; its Windows header was not sent on macOS                        |
| [SDL](https://github.com/libsdl-org/SDL/blob/6defbc0129b5ba90bab3f6c67bbc1e9329e24ff1/src/joystick/hidapi/SDL_hidapi_xboxone.c)                              | Home LED command; macOS code declines wired HID support because output reports are unavailable through its usual path          | Investigated the installed Apple driver's actual restrictions rather than treating the SDL limitation as a hardware limitation |
| [xbox-controller-driver-macos](https://github.com/golden-narwhal12/xbox-controller-driver-macos/blob/aef0575590b92ec0152659b08c758c6cfc5fc7bf/src/usb/usb.c) | libusb interface claim, interrupt OUT, start/LED initialisation for tested third-party wired controllers                       | Built and tested direct interrupt output on this Microsoft controller                                                          |
| [XboxOneControllerDev](https://github.com/JRHeaton/XboxOneControllerDev/blob/master/x1info/USBDevice.cpp)                                                    | Earlier macOS IOUSBLib interface and pipe access                                                                               | Confirmed the separate raw USB route; its old controller command format was not reused                                         |
| [PadForge tests](https://github.com/hifihedgehog/PadForge/blob/main/PadForge.Tests/GuideLedTests.cs)                                                         | Tests xbledctl-compatible Windows packets and the documented intensity ceiling                                                 | Corroborated driver-specific framing and the 0-47 range; test code alone is not hardware verification                          |

Consulted source snapshots remain in [target/led-project-research](target/led-project-research).
The firmware analysis, raw USB test, and shared HID test supply independent local
evidence. The successful HID framing was derived from this Mac's driver.

## Raw USB experiment

[XboxUsbRawLed.java](tools/XboxUsbRawLed.java) retains the earlier libusb test.
It validates identity, interface class and 64-byte interrupt endpoints `02/82`,
temporarily captures the controller, sends START `05 20 01 01 00` and LED
commands, then releases the interface and requests driver reattachment in cleanup.
Capture requires administrator authentication on this Mac and interrupts system
controller input while held. Driver reinitialisation may reset brightness.

The first authenticated run transferred five START bytes and seven LED bytes.
The breathing run completed 525 brightness writes; the user confirmed the light
glowed. Both interface release and driver reattachment returned success. This
establishes physical support on the connected controller despite the unavailable
exact installed firmware image. No firmware was updated.

Evidence: [raw transfers](target/firmware-analysis/usb-raw-led-admin-retry.txt),
[raw breathing](target/firmware-analysis/usb-raw-led-glow.txt),
[restored registry](target/firmware-analysis/usb-registry-after-raw-led.txt).
The shared HID utility above provides normal LED control without capture.

## Firmware readback attempt

Standard USB descriptor reads succeeded, but macOS denied libusb access to the
controller's GIP interface while Apple's driver remained attached. No verified
firmware readback command was identified for this device. This does not establish
that firmware extraction is impossible on another interface or host.

### Attempts and evidence

1. `ioreg -r -n Controller -l -w 0` confirmed the USB identity and driver ownership.
   Full output is in [xbox-usb-registry.txt](target/xbox-usb-registry.txt).
2. A temporary [Java probe](target/XboxUsbProbe.java) used Java 25 FFM and the
   installed Homebrew `libusb 1.0.30`. Inside the execution sandbox, enumeration
   returned no devices. Running the same probe outside the sandbox found the
   controller and successfully opened it.
3. Standard control request `80 06 00 02 00 00 00 10` (`GET_DESCRIPTOR`,
   configuration 0, buffer size 4096) returned 119 bytes. The saved
   [configuration descriptor](target/xbox-usb-configuration.bin) has SHA-256
   `5455aacefefdf3610b06f156585cfef4c6dae8e06e92cdb9a591f1f3e9ed2a2c`.
   These bytes describe USB interfaces and endpoints; they contain no firmware image.
4. `libusb_claim_interface(handle, 0)` returned `-3 (LIBUSB_ERROR_ACCESS)`.
   Driver detachment was disabled. The complete output is in
   [xbox-usb-probe-unsandboxed.txt](target/xbox-usb-probe-unsandboxed.txt).
5. The installed Apple driver was inspected with `nm -C` and `otool -arch arm64e -tvV`.
   `XboxWirelessGamepad::getReport` at address `0x10000a9a0` returns
   `0xe00002c7` unconditionally (`kIOReturnUnsupported`, confirmed in the installed
   IOKit header). This driver offers no HID `GetReport` readback path.
   See [disassembly](target/xbox-driver-arm64-disassembly.txt).
   Driver: `/System/Library/DriverExtensions/XboxGamepad.dext/XboxGamepad`, SHA-256
   `9e795737a95f163ddfca815cf9813c3391e6a1116d9150adb7300288b7704b9b`.

During this initial readback attempt, only standard descriptor requests
were sent to the controller. No GIP commands,
firmware updates, resets, driver detachments, calibration changes, or pairing
changes were performed. Device handles and the libusb context were closed.

### Protocol boundary

Microsoft's [GIP message summary](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-gipusb/c66cee2f-7728-4ec1-8f3c-e9ab3aa4d696)
does not specify a flash-read command. The independent
[protocol notes](https://gist.github.com/TheNathannator/c5d3b41a12db739b7ffc3d8d1a87c60a)
label `0x0c` as firmware data, but leave its semantics undocumented. That label
does not establish a firmware read operation. No guessed firmware commands were sent.

Further extraction needs both access to a suitable device interface and a
verified readback protocol. Raw GIP access on another host would remove the
current macOS ownership obstacle, but would not by itself establish firmware
readback support. An updater download would be a separate analysis artifact,
without proving that its bytes match this controller's flash.

No repeatable firmware dumper was added because extraction did not succeed.
The diagnostic probe and raw evidence remain local under `target/`.

## Native USB capture helper

[XboxUsbCapture.java](tools/XboxUsbCapture.java) prepares the alternative of
privileged capture through Apple's IOUSBHost API. It requires exactly one
`045e:0b12` controller, uses an explicit `--capture` argument, and calls `destroy`
in cleanup to release ownership and restore driver matching. The SDK documents
a USB reset during that cleanup. It does not send GIP commands or configure the
controller, and it does not dump firmware.

```sh
mise exec java@corretto-25 -- jbang tools/XboxUsbCapture.java --self-test
mise exec java@corretto-25 -- jbang tools/XboxUsbCapture.java --inspect
```

Both self-tests passed. Native discovery found exactly one connected controller.
This helper's capture mode was not exercised during the initial readback attempt:
`sudo -n` required a password, and the subsequent Microsoft download supplied
firmware for analysis without device capture. No administrator authentication
dialog was opened during that attempt.

The downloader completed a fresh HTTPS download and extraction. Both helpers
compiled with Java 25. Embedded-signature validation, this helper's privileged
capture mode and firmware readback remain unverified. The separate raw USB LED
experiment above subsequently succeeded with privileged libusb capture.
