# Firmware reverse engineering

Updated: 2026-10-24. Analysed application: Microsoft MERL `5.23.6.0`,
component 3, for the `045e:0b12` hardware family. The connected controller
reports `5.9.2709.0`; its exact application image remains unavailable.

The official update package supplied the analysis image. Firmware readback
did not succeed, and no firmware was written to the controller. The recovered
GIP LED command reaches a dedicated LED task and PWM driver; seven ARM
emulation tests verify its message and brightness handling. Physical USB
results and macOS driver framing are recorded in [USB](usb.md).

[Analysis](analysis.md) | [USB](usb.md) | [Bluetooth](bluetooth-macos.md)

## Download and package identity

[FetchXboxFirmware.java](../tools/FetchXboxFirmware.java) uses Java 25's standard
library, with no dependencies or USB access. It downloads one pinned Microsoft
package, verifies its SHA-256, USB identity, version and component bounds, then
saves the package and extracts the components. Repeated runs verify existing
files and refuse to replace different files.

```sh
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java

# Optional destination and offline checks.
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java target/another-copy
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java --self-test
```

Output directory: `target/firmware/MERL-5.23.6.0`.

> [!IMPORTANT]
> This is a downloaded updater image, separate from installed firmware. No flash
> dump was obtained and no firmware was updated. Embedded signatures remain unverified.

| File              | Version    | Load address | Bytes     | Contents                                      |
| ----------------- | ---------- | ------------ | --------- | --------------------------------------------- |
| `package.bin`     | 5.23.6.0   | Container    | 1,171,712 | Original `rdhm` package                       |
| `component-2.bin` | 5.23.6.0   | `0x10005000` | 139,264   | Payload format not established here           |
| `component-3.bin` | 5.23.6.0   | `0x10027000` | 516,096   | Application; Cortex-M-style vector table      |
| `component-4.bin` | 5.23.330.0 | `0x10027000` | 516,096   | Second application image; purpose unverified  |

Package SHA-256:
`8c2fb2ee745646d4021d00b28b6aefeb1b50a608a433a246d2bea5604dc0e693`.
Component 3 starts with stack pointer `0x20030d90` and reset vector `0x100271c1`,
consistent with a Cortex-M application at the declared address. Component 1
(bootloader) is absent from this package. This is an update payload, with no
device-specific calibration, pairing data or proof of the installed flash contents.
Embedded signatures have not been verified.

The official [PC feed](https://aka.ms/xboxaccessoriesfwcdnpcga) resolved to
[PC_GA_hqZ1pdWMjUW9sduWoLPDUQ.json](https://xbaccessories-bhf7bsbfgxdpbrg4.b02.azurefd.net/accessories/M/PC_GA_hqZ1pdWMjUW9sduWoLPDUQ.json).
It identifies `pc_ga` version `1.0.0.23`, and lists this
[MERL package](https://xbaccessories-bhf7bsbfgxdpbrg4.b02.azurefd.net/accessories/FW/MERL_045E_0B12_v_5_23_6_0_h_jC-y7nRWRtQCHQCyi2rv6xtQpgikM6JG0r6lYE3A5pM=.bin).
The [console feed](https://aka.ms/xboxaccessoriesfwcdnxbga), `xbox_ga` version
`1.0.0.20`, lists the same package. Both list `5.9.2709.0` as a minimum version,
without a download for that version. Other archives were not established as
Microsoft-hosted sources for the exact installed version.

The feed URL, filename hash encoding and `rdhm` layout were located through
[xboxctl's source](https://github.com/bb441db/xboxctl/tree/f83ec8c17f660df409713a83a355bd5d6fd87e1a),
then checked against the downloaded Microsoft index and package. That project
also documents UFU update commands; its documented interface provides versions
and flash hashes, without a byte-read command.

Local feed snapshots and download logs: `target/research/firmware/`. Feed versions,
package identity and hashes are recorded above.
Both GA feeds were downloaded again on 2026-10-24. They still advertise
`5.23.6.0` for MERL; the latest installed-controller read remains `5.9.2709.0`.
The [analysis version table](analysis.md#firmware-versions) separates advertised,
installed and analysed versions.

Refresh the two Microsoft indexes without changing a controller:

```sh
mkdir -p target/research/firmware
curl --fail --location https://aka.ms/xboxaccessoriesfwcdnxbga \
  --output target/research/firmware/xbox-ga-2026-10-24.json
curl --fail --location https://aka.ms/xboxaccessoriesfwcdnpcga \
  --output target/research/firmware/pc-ga-2026-10-24.json
```

In each index, use the `d` entry with `__n: merl`, `id: 045E_0B12`.
Its `f[].p` filename identifies the offered package version; `req[].v`
identifies a prerequisite. The downloader remains pinned for reproducible
analysis and does not select the latest feed entry automatically.

## Installed-version search: 5.9.2709.0

The [MERL archive](https://drive.google.com/drive/folders/1Yo8l4jqgqfHd2OJt_-VhKV_ntUA0U-GT)
lists `5.3.2569.0`, `5.11.3113.0`, `5.13.3143.0`, `5.13.3146.0`,
`5.20.7.0`, `5.22.16.0` and `5.23.6.0`. It has no `5.9.2709.0` entry.
The separate [legacy archive](https://drive.google.com/drive/folders/1-cVQrN7o5yv-V2fOO41AqyE01gdkKyNR)
contains four MERL manifests. All four were downloaded and inspected.

The oldest bundle listed in the
[Accessories archive](https://drive.google.com/drive/folders/1OYuGXa89gJIlrbUQIrF4JB-4tNjA7egV),
`Microsoft.XboxDevices_300.2202.25001.0` (February 2022), contains the same four
packages in its x64 APPX, with identical SHA-256 values:

| Embedded file                          | Package version | Application version |
| -------------------------------------- | --------------- | ------------------- |
| `045E_0B12_Manifest.bin`               | 5.13.3143.0     | 5.13.3143.0         |
| `045E_0B12_Manifest_Beta2.bin`         | 5.13.3143.0     | 5.13.3143.0         |
| `045E_0B12_Manifest_RecoveryA.bin`     | 5.3.2569.0      | 5.3.2569.0          |
| `045E_0B12_Manifest_RecoveryB.bin`     | 5.11.3113.0     | 5.11.3113.0         |

Each header identifies `045e:0b12`. Component table bounds and versions were
checked too; no component is labelled `5.9.2709.0`. Bundle SHA-256:
`70047e99acbda1209bee3b7b1120ccff47d59d85e86b7ec2c9b212d20b10ee59`.
These archive copies were inspected locally without installing or executing the
application. Their Microsoft signatures were not verified.

Internet Archive catalogue searches found no exact controller package or older
Accessories bundle. Wayback CDX queries for the current PC feed returned no
records. A broader filename query failed, so archived CDN coverage remains
unresolved. GitHub and web searches found version references without the image.

The [console updater archive](https://archive.org/details/xbox-one-osu1-updater-xvd-archive)
provided `updater.xvd` from `10.0.19041.8126`, June 2021. Its 103,469,056 bytes
match the archive's SHA-256:
`d1dedaa0fbd11059f6a92d60ddb60cb71a0c14acba0b06fb5da7192e2ce063f1`.
The XVD header has volume flags `1`: read-only, with encryption enabled under
[xvdtool's flag definitions](https://github.com/emoose/xvdtool/blob/6fc28862cc5fb711843c0e717101ce4034acf805/LibXboxOne/XVD/XVDEnums.cs).
Its filesystem was not decrypted or inspected. The adjacent August 2021 updater
download failed. Neither establishes whether the exact controller image is inside.

The remaining useful sources are an earlier Accessories installation or bundle,
an old Windows firmware cache, or an original Microsoft download URL. The cache
location reported by the [archive collector](https://www.reddit.com/r/XboxController/comments/1s8vvvw/looking_for_xbox_one_series_elite_12_controller/)
is `%LOCALAPPDATA%\Packages\Microsoft.XboxDevices_8wekyb3d8bbwe\LocalCache\FirmwareCache`.
Any candidate still needs its model, package and application versions checked.

Local archive listings, candidate-version output and XVD metadata remain in
`target/research/firmware/`. The downloader stays pinned to `5.23.6.0`.
No controller operations were performed during this search.

## Recovering the application correctly

The input application is 516,096 bytes, loaded at `0x10027000`, SHA-256:

```text
42e171de2834c4481177488f350cfc82e6fdae48a04137ac5ed468f6fae679b0
```

Analysing only this flash range misses executable code and handler tables.
Startup routine `0x10085808` walks relative initialisation records. Two invoke
the decompressor at `0x1002c7ae`:

| Record       | Compressed source | Input bytes | Destination  | Output bytes |
| ------------ | ----------------- | ----------- | ------------ | ------------ |
| `0x10086ab4` | `0x100887b0`      | 30,994      | `0x00100000` | 36,424       |
| `0x10086ac4` | `0x100900c2`      | 6,846       | `0x20004010` | 10,834       |

The Java decoder implements the firmware's literal and overlapping
back-reference format. Emulating the firmware decompressor produces exactly
the same bytes for both regions. The emulator stops before the final return
instruction because Ghidra 12.1.4's legacy ARM emulator fails its ISA switch
there; all decompression has completed at that point.

Recovered code SHA-256:
`a586482d0ae6f289d34213c16b6f3f766471914b5c305c6b0199ca337a796f58`.
Recovered data SHA-256:
`4a0a76866fb850cf927771766a2ea570c081616ae86752da3ae4f3dd12d09f21`.

The older archived MERL `5.11.3113.0` application contains a matching LED
handler structure at `0x1004a6d8`, also queuing task 8 with `0x0b00 | mode`.
Its brightness state offset is `0x114`. Its vendor-write handler at `0x10040978`
also queues seven bytes as host-manager command `0x28`. This supports continuity
across releases, without establishing the exact behaviour of installed `5.9.2709.0`.
The older `5.3.2569.0` image did not match the same function prefixes; no
compatibility conclusion follows. Archive signatures were not verified.

## The command and its path

Steady illumination at brightness step 10:

```text
0a 20 01 03 00 01 0a
```

| Bytes | Meaning                        |
| ----- | ------------------------------ |
| `0a`  | GIP LED opcode                 |
| `20`  | Internal-command flag          |
| `01`  | Packet sequence                |
| `03`  | Payload length                 |
| `00`  | Required leading payload byte  |
| `01`  | Steady mode; `00` requests off |
| `0a`  | Brightness step 10             |

Off uses `0a 20 01 03 00 00 00`. The layout independently agrees with
[xone's GIP implementation](https://github.com/medusalix/xone/blob/master/bus/protocol.c).
[xone's LED driver](https://github.com/medusalix/xone/blob/master/driver/common.c)
uses default step 20 and exposes a maximum of 50. Those are host policy values,
without a measured brightness curve or proof of this controller's physical maximum.
Microsoft's [LED command specification](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-gipusb/ec312389-2e05-4915-85ed-0e8fe9c3d33b)
documents intensity values 0-47, described as percentages, and reserves sequence
zero. The working utility follows that documented range.

The application reconstructs its custom dispatch table at `0x20004638`. The
10-byte entry at `0x20004642` maps command key `0x200a` to Thumb pointer
`0x1004ac05`, whose instruction address is `0x1004ac04`.

| Address                      | Recovered role                                           |
| ---------------------------- | -------------------------------------------------------- |
| `0x100545f0`                 | GIP packet reception/parsing                             |
| `0x100544de`, `0x1005424e`   | Packet assembly and command-table dispatch               |
| `0x1004ac04`                 | LED command handler                                      |
| `0x1003ff9c` -> `0x001043b4` | Task message queue                                       |
| `0x10074ffc`                 | Task 8: LED modes and patterns                           |
| `0x100756f6`                 | Task 26: hardware LED/PWM dispatch                       |
| `0x100757fc`                 | Clamp brightness to the current per-channel policy limit |
| `0x1007586c`, `0x10075950`   | Channel mapping and PWM update                           |
| `0x10075b48`                 | PWM register writes                                      |

For a non-empty payload whose first byte is zero, the handler copies payload
byte 2 into runtime state at offset `0x118`, then queues destination task 8,
message `0x0b00 | payload[1]`, and data starting with that brightness byte.
It ignores an empty payload or a non-zero leading byte. These are observations
of the handler, not a recommendation to send malformed lengths or modes.

Task 8 maps `0x0b00` to hardware-off message `0x0a00`. It maps `0x0b01` to
hardware-steady message `0x0a01`, carrying brightness. Other cases configure
timed patterns with constants 200, 600 and 3000; Microsoft's specification
identifies these as blink on-times in milliseconds. Those built-in patterns
were not tested visually. Task 26 ultimately drives memory-mapped PWM registers based at
`0x8300a700`, using configuration at `0x100848b8`.

The lower driver also limits its numeric duty value to 100 and applies board
configuration, with a mutable policy limit at `0x2002bee0`. Microsoft's protocol
unit is not a measured optical-brightness curve. The parser accepts 255 in
emulation; this exceeds the documented protocol range and is not used on hardware.
The traced command changes runtime LED state. No flash write was found in
this path. Physical retention results are recorded in [USB](usb.md#relative-state-and-retention).

### Recovered GIP dispatch table

Source: generated `gip-handlers.tsv` from the reconstructed RAM table.
Handler pointers retain their Thumb bit; instruction addresses clear bit 0.

| Entry address | Command key | Handler pointer | Flags  |
| ------------- | ----------- | --------------- | ------ |
| `20004638`    | `2006`      | `001026df`      | `0003` |
| `20004642`    | `200a`      | `1004ac05`      | `0000` |
| `2000464c`    | `001f`      | `1004aab1`      | `0000` |
| `20004656`    | `201f`      | `1004ab33`      | `0000` |
| `20004660`    | `0009`      | `1004aa51`      | `0003` |
| `2000466a`    | `200c`      | `1004ac87`      | `0000` |
| `20004674`    | `001e`      | `1004ac45`      | `0000` |
| `2000467e`    | `201e`      | `1004ac45`      | `0000` |

```mermaid
flowchart LR
    GIP[GIP key 0x200a] --> Handler[LED handler 0x1004ac04]
    Handler --> State[Runtime brightness at offset 0x118]
    State --> Task8[Task 8: LED mode and timing]
    Task8 --> Task26[Task 26: hardware LED dispatch]
    Task26 --> Clamp[Board policy and duty clamp]
    Clamp --> PWM[PWM registers at 0x8300a700]
```

## Defaults and saved configuration

The utility's `--on` selects step 20, following host software conventions. This
is not a recovered factory brightness. The internal LED task substitutes 100
when a steady or blink request has no brightness data; a normal GIP LED command
supplies that data, so this fallback does not establish the brightness at boot.

There is separate persistent LED configuration: `0x10074e96` reads two bytes from
EEPROM record `0x0c` into task state offsets `0x40` and `0x41`, with compiled
fallbacks `0x1a` and `0x3c`. The same task-13 operations are identified elsewhere
by `HostDeviceEepromRead Failed` and `HostDeviceEepromWrite Failed` diagnostics.
The bytes feed timer calculations at `0x10075268` and `0x100752e4`, rather than
holding the brightness supplied by the GIP command.

No command to save the requested brightness to controller memory was
identified. [USB retention results](usb.md#relative-state-and-retention) confirm
that the LED setting does not survive unplugging USB.

### Indirect brightness controls

The 2026-10-24 investigation excludes pairing, connection, identification and
blink patterns as brightness-control avenues. The strongest candidate is a
separate EEPROM-backed cap applied below the transport-specific LED handlers.

| Stage                  | Address or value               | Recovered behaviour                                                                                                  |
| ---------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| Load configuration     | `0x1004a380`                   | Reads logical EEPROM record 0, length 26, into application state at `0x200040c8 + 0x11`                              |
| Compiled fallback      | `0x1004adb4`, byte 22 (`0x16`) | Cap byte is `0x50` (80); the installed controller's record-0 byte 22 also reads as 80                                |
| Forward startup policy | `0x10052534`                   | Copies application offset `0x27` into task 26 initialisation command `0xff03`, length 1; runtime request becomes 100 |
| Set driver cap         | `0x10075404`                   | Stores initialisation byte at `0x2002bee0` when the hardware task is inactive                                        |
| Apply cap              | `0x100757fc`                   | Calls the off path for zero, otherwise forwards `min(requested, cap)` towards PWM                                    |
| Internal record writer | `0x1004a454`                   | Requests task 13 command `0x303` for record 0, length 26, using the cached configuration                             |

The cap is an internal numeric limit, not a percentage or the utility's
user-facing 0-47 range. Emulation runs the record loader, startup forwarding,
driver initialisation and clamp, with the EEPROM read mocked:

| Simulated record-0 cap | Request 20 reaches PWM as | Request 100 reaches PWM as |
| ---------------------- | ------------------------- | -------------------------- |
| 0                      | 0, off path               | 0, off path                |
| 5                      | 5                         | 5                          |
| 20                     | 20                        | 20                         |
| 80                     | 20                        | 80                         |

Changing this stored limit could constrain steady Bluetooth brightness after
startup. That is a candidate mechanism; persistence and installed-version
behaviour have not been tested on the physical controller. Repeating
initialisation while task 26 is already active leaves its cap unchanged.

The application has a concrete internal write candidate at `0x00102180`.
Its message handler is installed through the vtable at `0x10085958`, entry
`+0x18`, by constructor `0x1004a330`.

> An internal command is a scheduler message between firmware tasks. Its numeric
> identifier does not establish a USB opcode, HID report or Bluetooth packet.

Internal command 8 copies the cached 26-byte record to application offset
`0x2b`, then copies the supplied payload and length to offset `0x31` before
requesting the whole-record write. That destination corresponds to record
offset 6. An offline six-byte payload preserves the cap. A constructed 17-byte
payload reaches record offset 22 and changes only that byte from 80 to 5 while
preserving every other record byte. Execution stops at the EEPROM service
boundary, before a write. The factory dispatcher also accepts a 17-byte
command-8 payload at permission level 1 in emulation, as described below.
These checks establish the separate dispatch and copy operations. The
[physical campaign](brightness-campaign.md#recorded-physical-results-2026-10-24)
subsequently read the installed record without writing it.

The EEPROM service supplies an additional boundary. Its record table at
`0x10079c34` gives record 0 logical address `0x0060` and a maximum payload of
29 bytes; this application reads and writes 26 bytes. Emulating `0x1006c490`
verifies that lookup. Emulating `0x1006c77a` verifies the storage frame:
record ID, payload length, payload, then the eight-bit additive payload checksum.
A 26-byte payload therefore occupies 29 bytes including the header and checksum.
The synchronous write helper at `0x1006c810` calls the NV manager through
`0x1006d4ae`, reads the block back through `0x1006d40c`, and checks its ID,
length, data and checksum. Storage calls were inspected, without executing them.

Record 0 also carries device identity data. A usable persistent-control command
requires an identified ingress, a complete record read, a preserved original,
verified write/restore semantics, and validation on the installed firmware.
The physical USB read returned two identical 32-byte slots with ID 0, payload
length 26 and a valid checksum. Payload byte 22 was 80. The installed firmware
returns a two-byte address for record lookup, matching the Windows USB driver;
the analysed newer image returns capacity and address in three bytes. Reading
the stored byte does not establish its optical effect on installed firmware.

The Windows driver's record-write command is `0x0306`, selector 3, with inline
payload `{recordId, length, data...}`. NV dispatcher `0x1006c90e` passes that
payload to the record writer. After explicit authorisation, the physical
campaign attempted this command with the original record unchanged. No matching
completion arrived; two subsequent reads verified the original full slot
intact. The runner stopped before cap 5. No working write or restoration was
established. No factory reset or firmware update was performed.

#### Factory command ingress and permissions

The [Windows USB driver](windows-drivers.md#usb-and-xbox-wireless-test-commands)
exposes vendor requests and a GIP test-command wrapper. Both map external
selectors to internal tasks through the table at `0x1007ac08`:

| Selector | Internal task    | Relevant operation                                            |
| -------- | ---------------- | ------------------------------------------------------------- |
| 3        | 13, NV manager   | Record lookup `0x308`, raw read `0x300`, record write `0x306` |
| 10       | 26, LED hardware | Hardware LED messages                                         |
| 11       | 8, LED modes     | Steady brightness `0x0b01`                                    |
| 14       | 4, application   | Configuration copy, command 8                                 |
| 22       | 18, memory       | Memory read `0x1601`                                          |

For USB, `0x1005d26c` accepts vendor OUT `bmRequestType=0x40`, request
`0x10`, subject to command permission. `0x1005d180` reads the selector from
the high byte of `wValue`, flags from its low byte, and the message code from
`wIndex`, then queues the request with its data length. The observed driver
EEPROM requests use selector 3. Vendor IN `c0 00`, value `0x0008`, index
`0xff00` obtains the buffered response when permission allows it. This
translation was recovered statically. The later physical campaign confirmed
endpoint-zero vendor transfers, factory LED control and record reads while
Apple's driver remained attached.

For GIP, opcode `0x1f`, flags zero, payload beginning with 2 reaches handler
`0x1004aab0`, which strips that byte and queues task 3 command `0x20e`.
Dispatcher `0x1006f972` expects this remaining frame:

```text
flags:u8 selector:u8 message:LE16 length:LE16 data:length
```

It requires at least six header bytes, a complete declared payload, a permitted
command, and a selector mapping below task 30. The permission gate at
`0x10054890` compares the full `message | flags << 16` value on this path;
the USB admission check uses the 16-bit message. Passing a USB flag byte
through the GIP wrapper therefore does not establish identical admission.

The reconstructed startup value at `0x20006a5a` is permission level 2.
Its whitelist at `0x100885b8` contains only `0xff00`, `0x020f`, `0x0210`
and `0x0211`. Three sequential internal requests, `0x020f` with `0x51db`,
`0x0210` with `0xff6e`, then `0x0211` with `0xabaa`, lower the RAM permission
to level 1. A wrong final key resets the sequence and leaves permission at 2.
The later USB campaign returned a successful `0x0211` reply after these keys,
then accepted the LED and NV read commands. BLE writes carrying the keys were
acknowledged without a visible light change or evidence of an internal unlock.

Level 1 permits application command 8, LED `0x0b01`, and NV reads `0x300`
and `0x308`. The 127-entry whitelist at `0x1007ab08` excludes record write
`0x0306`, memory read `0x1601` and RAM write `0x1605`. Permission level 0
admits all messages, but no authorised application-mode route to that level
has been established. A live `0x1601` read at `0x10027000` returned no matching
reply after the normal unlock; no application image was obtained. The read
and unchanged-record write failures are consistent with this gate, without
proving the installed older firmware uses the same implementation.

Offline execution verifies 36 gate cases, 16 dispatcher cases
and six unlock steps. The dispatcher checks cover command 8 with lengths
6 and 17, steady LED and record lookup; level-2 and truncated frames enqueue
nothing. Execution stops before the scheduler processes a request. These are
separate fixture checks, without full transport, scheduler or storage emulation.

This resolves a factory ingress to the configuration candidate for USB/GIP.
The existing generic GIP ingress rejects BLE source task 24 before parsing,
and the BLE GATT callbacks do not feed this factory dispatcher. A factory
message number is therefore insufficient to construct a new XIAO BLE trial.

#### L2CAP channel registry

The nine-entry registry at `0x1007b388`, count byte `0x100421f6`, is consulted
by `0x1003c80c` and `0x1003c888`. Its entries contain two callback pointers.
The offline checks execute both callbacks for the first eight entries:

| PSM                | Registered callbacks in this image              |
| ------------------ | ----------------------------------------------- |
| `0x0001`, `0x0003` | Constant return `0x03fffff1`                    |
| `0x0017`, `0x001b` | First returns zero, second returns `0x03fffff1` |
| `0x0019`, `0x3a27` | Constant return `0x03fffff1`                    |
| `0x0011`, `0x0013` | Constant return `0x03fffff1`                    |
| `0x001f`           | ATT functions `0x100791bc` and `0x100790da`     |

The unfamiliar `0x3a27` entry supplies two constant-return stubs, without
command processing. It also lies outside the
[Bluetooth LE SPSM range](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/logical-link-control-and-adaptation-protocol-specification.html):
LE credit-based services use `0x0001` through `0x00ff`. No distinct LE GIP
service was identified in this registry. This is a bounded result for the
analysed image, without testing arbitrary channels on installed firmware.

#### Other indirect controls and verification

Other indirect mechanisms were checked against the same pinned application:

| Candidate                | Offline evidence                                                                                                                     | Result and boundary                                                                                                   |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| Colour weighting         | `0x10075908` stores coefficients 0, 1, 20, 100 and 255; `0x1007586c` bypasses scaling for the one-channel descriptor at `0x1007d8a8` | All five cases forward duty 20 for request 20; no dimming through coefficients on this board                          |
| Rumble flag bits         | `0x10078438`, all 128 flag values reachable after the BLE callback clears bit 7                                                      | Bits 0-3 select motors; bits 4-6 supply no LED route; application brightness and cap stay unchanged                   |
| Low-battery motor policy | `0x100783d4`, battery levels 0-4                                                                                                     | Levels below 2 stop two grip motors; this callback leaves LED brightness and cap unchanged                            |
| Shared PWM configuration | Output table `0x100848b8`; motor mapping `0x10076210`                                                                                | LED uses group 0, channel `0x25`; motors use different groups and channels; analogue supply effects remain unmeasured |

The checks are implemented in
[XboxIndirectBrightness.java](../tools/ghidra/XboxIndirectBrightness.java).
They execute firmware instructions with synthetic RAM fixtures and mock
EEPROM reads, diagnostics, scheduler responses and motor hardware calls.
Thumb calls and returns are implemented explicitly to work around the legacy
emulator's ISA-switch failure; three firmware `memcpy` cases check that helper.
The tests establish bounded data and control flow, without simulating the radio,
full scheduler, non-volatile storage or physical light output.

```sh
GHIDRA_INSTALL_DIR="$PWD/target/ghidra-native/install" \
  mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java indirect

# Inspect the configuration loader, startup forwarding and internal message handler.
GHIDRA_INSTALL_DIR="$PWD/target/ghidra-native/install" \
  mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java \
  inspect 1004a380 10052534 00102180 1004a454
```

The first command writes `target/firmware-analysis/indirect-brightness-verification.txt`
and requires a final PASS marker. Both commands use the existing Ghidra project
read-only and never open a controller.

## Bluetooth vendor service

Reconstructing initialised RAM exposes all 26 GATT registration entries at
`0x20004c5c`, including the previously unexplained vendor service. Its UUID
suffix is `-5f60-4c4f-9c83-a7953298d40d`.

| Characteristic prefix | Callback     | Behaviour in this application                                                                  |
| --------------------- | ------------ | ---------------------------------------------------------------------------------------------- |
| `00000002`            | `0x1004095a` | Telemetry read; embedded diagnostic name `HBLETelemetryReadCallback`                           |
| `00000003`            | `0x1004099c` | Host-list read; diagnostic name `HBLEHostListCallback`; requests host-manager message `0x159d` |
| `00000004`            | `0x100409ec` | Write queues exactly seven bytes to task 29, command `0x28`                                    |

The write callback forwards data from ATT event offset `0x2a`, with source task
24 and destination task 29, then acknowledges the ATT operation. ARM emulation
independently verifies those queue arguments. It does not interpret a supplied
GIP header or forward a brightness byte to LED task 8.

Task 29's host-manager dispatcher is at `0x00103544`; its state table is at
`0x1004ef78`, with a second enrolment state table at `0x1004ef8c`. Recovered
states handle connection, enrolment and host events, with some LED patterns
triggered by those events. In the connected wireless state (`0x00106cf8`),
command `0x28` reaches `0x0010741e`, which calls the unknown-command logger
at `0x1004a9ae` through thunk `0x00102160`. ARM emulation confirms this for
payloads beginning with `00`, `01`, `0a` and `ff`: no task dispatch occurs
before that logger, and the brightness state remains unchanged. This identifies
an ignored command in this state; it does not establish its intended purpose
or behaviour in every firmware version and host state.

The standard Bluetooth HID output callback at `0x100408de` handles rumble and
queues task 10, message `0x1200`. Emulation confirms that lengths 0, 3 and 7
return an error; lengths 8 and 13 forward only the first eight bytes as rumble,
even when the bytes start with the GIP LED opcode. The HID Control Point callback
at `0x1004072c` only tail-calls the `LEHIDCtrl` diagnostic logger. These paths
supply no generic GIP tunnel or suspend-to-dim command in the analysed image.
These findings provide no verified Bluetooth brightness command and do not
prove the absence of every possible command. The later
[hardware test](bluetooth-macos.md#hidden-hid-and-vendor-writes)
sent four GIP LED packets to the vendor characteristic on installed `5.9.2709.0`.
All were acknowledged; the user observed no LED change.

The Bluetooth-path checks are in [XboxBluetooth.java](../tools/ghidra/XboxBluetooth.java).
They run against the pinned, reconstructed application and stop before external
calls. One diagnostic logger is skipped to reach the short-write error response.
Ghidra's legacy ARM emulator cannot execute the ISA-mode update on the final
branch-and-link; the tests assert the call-site registers and thunk destination
instead. They do not emulate the Bluetooth radio, the scheduler or hardware.
Results are embedded in the verification table below.

### Console brightness and Bluetooth ingress

Microsoft's [night-mode announcement](https://news.xbox.com/en-us/2021/10/21/october-2021-xbox-update/)
confirms controller dimming on Xbox consoles. The controller's
[connectivity specification](https://www.xbox.com/en-US/accessories/controllers/xbox-wireless-controller)
separates Xbox Wireless for console connections from Bluetooth for PCs and
mobile devices. A console's brightness capability establishes a working
controller LED path, without establishing a Bluetooth command.

The later [Windows driver analysis](windows-drivers.md) traces
`SetHomeLedIntensity` through IOCTL `0x40001c60` to a host policy and ordinary
GIP `0x0a` packets. Its separate XInput HID LED-state operation updates a host
cache without sending HID output. The console UI's implementation remains
unverified.

The [xone LED command](https://github.com/medusalix/xone/blob/master/bus/protocol.c)
uses GIP opcode `0x0a`, mode and brightness. Its
[wireless transport](https://github.com/medusalix/xone/blob/master/transport/dongle.c)
uses MediaTek/IEEE 802.11 framing. The XIAO's
[nRF52840 radio](https://docs.nordicsemi.com/r/bundle/ps_nrf52840/page/keyfeatures_html5.html)
supports BLE, IEEE 802.15.4 and Nordic proprietary FSK modes, and cannot substitute
for that Xbox Wireless transport.

The reconstructed firmware supplies a matching boundary at `0x1004acd2`:

| Sender task | Recovered route                                       | Emulation result                               |
| ----------- | ----------------------------------------------------- | ---------------------------------------------- |
| `4`         | USB-side ingress into `0x100545f0`, transport value 1 | Payload pointer and length forwarded unchanged |
| `17`        | Radio-side ingress, transport value 2                 | Payload pointer and length forwarded unchanged |
| `24`        | Bluetooth task                                        | Assertion at `0x1004acfc`, before parser entry |

These checks stop before the parser branch or assertion call. The ingress
routine also contains an assertion for sender task `14`; that branch was
inspected but is outside the three emulated cases. The ordinary HID output
callback takes its separate rumble path and does not enter this GIP receiver.

The assertion thunk at `0x1002aa4c` points to `0x20005830`, executable code
inside the reconstructed RAM initialisation region. Its handler records the
call location and line, invokes diagnostic helpers, optionally executes
`bkpt 0xff`, and ends in an infinite branch at `0x20005858`. It supplies no
return to the GIP parser. The ingress checks verify both the thunk destination
and that terminal branch; they do not trigger an assertion on the controller.

```sh
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java \
  inspect 1004acd2 1002aa50 code:20005830
```

The LED driver clamps its requested value against the channel policy byte at
`0x2002bee0`. Sixteen emulation cases verify `min(requested, cap)` for requests
0, 5, 20 and 47 and caps 0, 5, 20 and 100. Zero selects the off path. The policy
setter at `0x10075404` receives task-initialisation data and writes the cap;
no Bluetooth-accessible policy setter was established.

Vendor command `0x28` was checked in idle, detection, enrolment, switching,
connected radio, connected BLE and connected USB states. The five enrolment
substates were checked separately. Each used payload first bytes `00`, `0a`
and `ff`, with source task 24 and length 7. All 36 checks reached return,
unknown-command or enrolment-subdispatch boundaries without changing the
application brightness byte or reaching a task queue. The enrolment parent
stops before calling its subdispatcher; the five callback checks cover those
substates independently. The initial diagnostic logger is skipped in the
detection state. These are bounded control-flow checks, not scheduler emulation.

Together with the ten earlier Bluetooth checks, the three ingress cases and
sixteen clamp cases produce 65 passing checks under Ghidra 12.2. They analyse
`5.23.6.0`; the controller still runs `5.9.2709.0`. The installed-version
difference remains a reason to test its HID Control Point directly. The
[XIAO reference](bluetooth-xiao.md) records direct writes and the additional
Suspend/Resume experiment.

<details>
<summary>All 26 recovered GATT registration entries</summary>

Source: generated `gatt-handlers.tsv`. Type and flags are raw firmware fields,
not a direct substitution for the observed ATT property bits. Zero callback
means no callback pointer in this entry. Vendor UUID suffix:
`-5f60-4c4f-9c83-a7953298d40d`.

| Entry address | Type | UUID                                   | Raw flags | Callback pointer |
| ------------- | ---- | -------------------------------------- | --------- | ---------------- |
| `20004c5c`    | `0`  | `1800`                                 | `0x0000`  | `00000000`       |
| `20004c78`    | `3`  | `2A00`                                 | `0x0082`  | `00000000`       |
| `20004c94`    | `3`  | `2A01`                                 | `0x0082`  | `00000000`       |
| `20004cb0`    | `3`  | `2A04`                                 | `0x0082`  | `10040783`       |
| `20004ccc`    | `0`  | `1801`                                 | `0x0000`  | `00000000`       |
| `20004ce8`    | `0`  | `180A`                                 | `0x0000`  | `00000000`       |
| `20004d04`    | `3`  | `2A29`                                 | `0x0082`  | `00000000`       |
| `20004d20`    | `3`  | `2A50`                                 | `0x0082`  | `00000000`       |
| `20004d3c`    | `3`  | `2A26`                                 | `0x0082`  | `00000000`       |
| `20004d58`    | `3`  | `2A25`                                 | `0x0082`  | `00000000`       |
| `20004d74`    | `0`  | `180F`                                 | `0x0000`  | `00000000`       |
| `20004d90`    | `3`  | `2A19`                                 | `0x0092`  | `00000000`       |
| `20004dac`    | `4`  | `2902`                                 | `0x00c0`  | `100408d7`       |
| `20004dc8`    | `0`  | `1812`                                 | `0x0000`  | `00000000`       |
| `20004de4`    | `3`  | `2A4A`                                 | `0x0182`  | `00000000`       |
| `20004e00`    | `3`  | `2A4C`                                 | `0x0044`  | `1004072d`       |
| `20004e1c`    | `3`  | `2A4B`                                 | `0x0182`  | `10040813`       |
| `20004e38`    | `3`  | `2A4D`                                 | `0x0193`  | `00000000`       |
| `20004e54`    | `4`  | `2902`                                 | `0x00c0`  | `100408db`       |
| `20004e70`    | `4`  | `2908`                                 | `0x0080`  | `00000000`       |
| `20004e8c`    | `3`  | `2A4D`                                 | `0x01cf`  | `100408df`       |
| `20004ea8`    | `4`  | `2908`                                 | `0x0080`  | `00000000`       |
| `20004ec4`    | `0`  | `00000001-5f60-4c4f-9c83-a7953298d40d` | `0x0000`  | `00000000`       |
| `20004ee0`    | `3`  | `00000002-5f60-4c4f-9c83-a7953298d40d` | `0x0182`  | `1004095b`       |
| `20004efc`    | `3`  | `00000003-5f60-4c4f-9c83-a7953298d40d` | `0x0182`  | `1004099d`       |
| `20004f18`    | `3`  | `00000004-5f60-4c4f-9c83-a7953298d40d` | `0x0148`  | `100409ed`       |

</details>

## Repeat the offline analysis

[ReverseXboxFirmware.java](../tools/ReverseXboxFirmware.java) launches the installed
Ghidra and [XboxFirmware.java](../tools/ghidra/XboxFirmware.java), pins the input
hash and vectors, reconstructs startup memory, seeds callbacks and exports
decompiled C, instruction listings and references. It never opens a controller.

Requires Java 25, JBang and Ghidra; tested with Ghidra 12.1.4. The default
installation is `/opt/homebrew/opt/ghidra/libexec`; set `GHIDRA_INSTALL_DIR`
for another installation. From this checkout:

```sh
export JBANG_DIR="$PWD/target/jbang"
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java --self-test

# Run import and map-startup once for a new analysis project.
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java import
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java map-startup
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java handlers
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java bluetooth
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java indirect

# Read-only inspection of selected addresses; code: defines a missing function temporarily.
mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java inspect 1004ac04 10074ffc 100756f6
```

The local Ghidra 12.2 package had no `decompile` executable for Apple Silicon.
The additional checks used a private copy under `target/`, with its bundled
native source compiled locally. The installed Ghidra was unchanged:

```sh
mkdir -p target/ghidra-native
cp -cR "$(brew --prefix ghidra)/libexec" target/ghidra-native/install
cp -R "$(brew --prefix ghidra)/libexec/Ghidra/Features/Decompiler/src/decompile/cpp" target/ghidra-native/cpp
make -C target/ghidra-native/cpp -j4 ghidra_opt \
  ARCH_TYPE='-arch arm64' ADDITIONAL_FLAGS='-mmacosx-version-min=13.0'
mkdir -p target/ghidra-native/install/Ghidra/Features/Decompiler/os/mac_arm_64
cp target/ghidra-native/cpp/ghidra_opt \
  target/ghidra-native/install/Ghidra/Features/Decompiler/os/mac_arm_64/decompile

GHIDRA_INSTALL_DIR="$PWD/target/ghidra-native/install" \
  mise exec java@corretto-25 -- jbang tools/ReverseXboxFirmware.java bluetooth
```

Run the copy commands once into a new `target/ghidra-native` directory. The
emulation and inspection commands continue to use a read-only firmware project.

### Output layout

| Location under `target/firmware-analysis/`                                                                     | Contents                                                                |
| -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `projects/`, `settings/`, `cache/`                                                                             | Ghidra working state                                                    |
| `decompiled.c`, `functions.tsv`, `instructions.txt`                                                            | Decompiled application, function index and instruction listing          |
| `gip-handlers.tsv`, `gatt-handlers.tsv`                                                                        | Dispatch tables reproduced above                                        |
| `string-references.tsv`                                                                                        | String cross-references; diagnostic names cited in handler descriptions |
| `startup-verification.txt`, `led-verification.txt`, `gatt-verification.txt`, `bluetooth-path-verification.txt` | Emulation results reproduced below                                      |

### Recorded emulation results

| Check                       | Inputs                             | Verified result                                                                      |
| --------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------ |
| Startup decompression       | Code and data regions              | Firmware emulator and Java decoder produce identical 36,424- and 10,834-byte outputs |
| LED handler, steady         | Steps 10, 20, 50 and 255           | Four task-8 messages and runtime brightness values match                             |
| LED handler, off            | Mode 0, step 0                     | Task-8 off message verified                                                          |
| LED handler, empty          | Length 0                           | Ignored; no state change                                                             |
| LED handler, reserved byte  | Leading byte 1                     | Ignored; no state change                                                             |
| Vendor write callback       | Seven bytes                        | Queues task 29, command `0x28`; no GIP opcode dispatch                               |
| Connected vendor dispatcher | First bytes `00`, `01`, `0a`, `ff` | Four checks: unknown-command logger, brightness unchanged, no task dispatch          |
| HID output, short           | Lengths 0, 3, 7                    | Three checks: callback rejects                                                       |
| HID output, accepted        | Lengths 8, 13                      | Two checks: first eight bytes queued as rumble, task 10, message `0x1200`            |
| HID Control Point           | Callback path                      | One check: tail-call to `LEHIDCtrl` logger only                                      |

> [!NOTE]
> Steps 50 and 255 test the parser in emulation only. Hardware commands use the
> documented 0-47 range. These checks do not emulate the radio, scheduler or hardware.

Function names assigned by the script describe recovered roles. Decompiled C
has inferred types and calling conventions. Ghidra also tries to decompile
some compressed source bytes as instructions and warns at `0x10088bd4`,
`0x10088c96`, `0x10088cae` and `0x1008c77a`. Those bytes are not evidence for
the LED path. The important handler was checked against its instructions and
emulation. The entire scheduler, hardware and application were not emulated.
