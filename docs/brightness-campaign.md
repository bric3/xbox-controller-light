# Guided brightness campaign

[Analysis](analysis.md) | [XIAO setup](bluetooth-xiao.md) | [Windows drivers](windows-drivers.md) | [Firmware](firmware.md)

This campaign tests recovered runtime command formats on the physical controller,
reads its configuration and application memory, and provides a separately
authorised EEPROM cap experiment.
The BLE carrier is a hypothesis: the analysed `5.23.6.0` application does not
route its vendor characteristic to the factory dispatcher. The controller was
updated from `5.9.2709.0` to `5.23.6.0`; the post-update campaign below confirms
the new version over GATT. An ATT success establishes
acceptance of the write, separately from command execution or visible dimming.

## Post-update results: 5.23.6.0

On 2026-10-24, fresh XIAO pairing established `SECURITY 1 2 16` and read
GATT Firmware Revision `5.23.6.0`. Discovery found six primary services,
the HID input/output reports and vendor write handle `002a`. Fresh controller
input was verified before the LED trials. Each LED trial sent four requests,
alternating steps 0 and 20, with observations recorded through native dialogs.

| Trial                        | Transport result                                               | Visual observation |
| ---------------------------- | -------------------------------------------------------------- | ------------------ |
| BLE raw factory LED          | Four writes and cleanup acknowledged                           | Unchanged          |
| BLE GIP factory LED          | Four writes and cleanup acknowledged                           | Unchanged          |
| BLE raw factory unlock + LED | Three keys, four writes and cleanup acknowledged               | Unchanged          |
| BLE GIP factory unlock + LED | Three keys, four writes and cleanup acknowledged               | Unchanged          |
| Seven-byte HID GIP           | Four writes rejected with ATT `0x0180`                         | Unchanged          |
| Thirteen-byte HID GIP        | Four writes acknowledged                                       | Unchanged          |
| Seven-byte vendor GIP        | Four writes acknowledged                                       | Unchanged          |
| HID Suspend/Resume           | Both Write Commands transmitted; encrypted connection retained | Unchanged          |
| USB shared HID baseline      | Four brightness writes accepted                                | Changed            |
| USB factory LED              | Four requests with matching successful `0x0b01` replies        | Changed            |

USB factory unlock returned a matching successful `0x0211` reply. Record-0
lookup still returned the two-byte address `60 00`. Two full-slot reads were
identical, with record ID 0, length 26 and a valid checksum; payload byte 22
remains 80. The saved 32-byte slot also matches the pre-update snapshot from
`2026-10-24T15-06-39.110669Z` byte for byte (`cmp` exit status 0).
USB cleanup restored step 20, followed by a confirmed power cycle. After HID
Resume, input notifications increased from 43 to 206. All three runners exited
with status 0; both BLE sessions ended with confirmed disconnection.

Private evidence:

- Factory BLE and USB: `target/campaigns/2026-10-24T17-25-54.193956Z/`, with serial log `target/xiao/runs/2026-10-24T17-26-02.251263Z.log`.
- Standard BLE LED formats: `target/xiao/runs/2026-10-24T17-32-32.813034Z.log`.
- HID Suspend/Resume: `target/xiao/runs/2026-10-24T17-35-18.234066Z.log`.

Two earlier follow-up runs, `17-30-19.891531Z` and `17-31-24.314125Z`,
stopped during discovery with XIAO `ERROR SOFTDEVICE 00000011`, before LED
writes. Nordic defines that status as busy. Discovery/read requests rejected
with that status now receive up to ten retries, 100 ms apart. Writes, other
errors and timeouts stop the request. Self-tests cover the retry boundary and
command exclusions; the subsequent full runs completed without another busy error.

These trials used the analysed application release. No EEPROM write, cap
change or application flash-read attempt was included. The built-in macOS
Bluetooth path has not been retested on `5.23.6.0`.

## Run

Keep the XIAO connected. Close serial monitors before running:

```sh
mise exec java@corretto-25 -- jbang tools/XboxBrightnessCampaign.java --self-test
mise exec java@corretto-25 -- jbang tools/XboxBrightnessCampaign.java --run /dev/cu.usbmodem1101

# Repeat the standard HID/vendor LED formats and HID Suspend/Resume.
mise exec java@corretto-25 -- jbang tools/XboxBleExperiment.java --run /dev/cu.usbmodem1101
mise exec java@corretto-25 -- jbang tools/XboxBleExperiment.java --suspend-test /dev/cu.usbmodem1101

# Resume with the USB stages only; no XIAO port is required.
mise exec java@corretto-25 -- jbang tools/XboxBrightnessCampaign.java --usb-only

# Resume only EEPROM inspection after completed brightness trials.
mise exec java@corretto-25 -- jbang tools/XboxBrightnessCampaign.java --usb-read

# Attempt a read-only application dump using the Windows driver's command.
mise exec java@corretto-25 -- jbang tools/XboxBrightnessCampaign.java --usb-image
```

The runner uses the existing Java/AppKit dialogs and XIAO sketch. It requires
no new Arduino upload. Arduino CLI installation through Homebrew and the sketch
build/upload commands are in [XIAO setup](bluetooth-xiao.md).

Every physical step has an untimed OK/Cancel dialog. Progress windows pump
AppKit events. Each observation has Changed, Unchanged, Didn't see and Cancel
buttons. Didn't see repeats that trial; Cancel stops it. If the controller
sleeps before a BLE trial, the runner returns to connection setup and checks
the GATT schema and serial number again.

Each light sequence requests steps 0, 20, 0 and 20, three seconds apart.
Pairing flashes and disconnects do not count as a positive result. A reported
BLE change must repeat after reconnecting before the campaign treats it as a
reproduced observation. That result stops the campaign for analysis.

## BLE trials through the XIAO

The runner discovers the vendor write characteristic and HID input CCC. It
checks Microsoft product identity, reads the firmware and serial number, and
requires fresh controller input before trials. It writes only the discovered
vendor characteristic for these candidates.

| Trial                        | Format                                                          | Preparation                 |
| ---------------------------- | --------------------------------------------------------------- | --------------------------- |
| BLE raw factory LED          | Seven-byte internal test frame                                  | Encrypted connection        |
| BLE GIP factory LED          | GIP `0x1f`, flags zero, leading payload byte 2, then test frame | Encrypted connection        |
| BLE raw factory unlock + LED | Raw unlock frames followed by raw LED frames                    | Three volatile factory keys |
| BLE GIP factory unlock + LED | GIP-wrapped unlock frames followed by wrapped LED frames        | Three volatile factory keys |

The internal frame is `flags:u8 selector:u8 message:LE16 length:LE16 data`.
Campaign frames use flags zero. Selector 11 maps to LED task 8; `0x0b01`
requests steady light with one brightness byte:

```text
Raw LED off:       00 0b 01 0b 01 00 00
Raw LED step 20:   00 0b 01 0b 01 00 14
GIP LED off:       1f 00 01 08 02 00 0b 01 0b 01 00 00

Raw unlock key 1:  00 02 0f 02 02 00 db 51
Raw unlock key 2:  00 02 10 02 02 00 6e ff
Raw unlock key 3:  00 02 11 02 02 00 aa ab
```

The GIP sequence increments between writes. Raw frames have no GIP sequence.
Unlock selector 2 maps to test task 3. These formats come from the recovered
factory dispatcher; their acceptance through BLE is the hypothesis being tested.
The newer firmware's vendor callback copies seven bytes to an unrelated
host-manager command, so unchanged results are expected for that implementation.
Longer ATT writes alone do not establish a longer internal command.

The runner records each packet, ATT result, connection status and user
observation. It requests step 20 through the trial's carrier during cleanup.
No new candidate is sent as a HID rumble output report.

## USB stages

The runner requests a USB cable connection in a separate dialog after the BLE
trials. It checks the previously working shared-HID LED command first. A
positive visual baseline is required before factory tests.

The vendor requests use libusb endpoint-zero control transfers. The runner
checks one `045e:0b12` controller and its configuration, and opens the device
without claiming an interface or detaching Apple's driver. A denied or stalled
request stops this stage and is recorded.

Microsoft's `dc1-controller.sys!DccUsbFwUnlockDevice` at `0x1c0008d84`
supplies the exact three USB unlock requests below. After unlocking, the
runner tests the internal steady-LED command, looks up record 0 and attempts
two reads of its slot:

| Operation          | bmRequestType | bRequest | wValue | wIndex | Data or requested IN length |
| ------------------ | ------------- | -------- | ------ | ------ | --------------------------- |
| Unlock 1           | `40`          | `10`     | `020c` | `020f` | `db 51`                     |
| Unlock 2           | `40`          | `10`     | `020c` | `0210` | `6e ff`                     |
| Unlock 3           | `40`          | `10`     | `020c` | `0211` | `aa ab`                     |
| LED, steady        | `40`          | `10`     | `0b0c` | `0b01` | One byte: `00` or `14`      |
| Record-0 lookup    | `40`          | `10`     | `030e` | `0308` | `00 00`                     |
| Record-0 slot read | `40`          | `10`     | `030e` | `0300` | `60 00 20`                  |
| Reply polling      | `c0`          | `00`     | `0008` | `ff00` | 64 bytes                    |

All multi-byte values in request data are little-endian. The USB API passes
`wValue` and `wIndex` as 16-bit values. The low `wValue` byte supplies internal
message flags; its high byte supplies the selector.

Reply parsing requires a successful status, matching 16-bit command and a
complete declared payload after the four-byte header. Busy replies are polled
with bounded retries. Polling requests 64 bytes to include the header and full
data, rather than copying the legacy driver's ambiguous length accounting.

Raw EEPROM reads require lookup payload `1d 60 00` (capacity 29 and logical
address `0x0060`) or legacy payload `60 00` (the address alone). The legacy
layout was observed on installed firmware and matches Microsoft's USB driver.
A different address or layout stops the campaign before the raw read.
The 32-byte slot covers the maximum 29-byte payload plus ID, length and checksum.
The runner requires ID 0, payload length 26-29, a valid eight-bit additive
payload checksum, and two identical reads. Payload byte 22 is the cap candidate
identified in the newer firmware; its meaning on installed firmware requires
separate validation. A matching read is a snapshot, without a verified restore
operation.

The runtime runner's command whitelist permits the LED operation, the three
exact unlock keys, record-0 lookup and its fixed slot read. The `--usb-image`
mode also permits command `0x1601`, selector 22, with a five-byte payload
`{addressLE32, length:u8}`. Length is 1-48 bytes and the address must fall within
the analysed application range `0x10027000` through `0x100a4fff`. This mode
checks the vector table twice before attempting two complete matching reads.
The runtime runner rejects application command 8, NV writes, task
reinitialisation, other addresses and firmware updates.
USB cleanup requests the known HID brightness step 20. After any unlock attempt,
a final dialog asks for a power cycle to clear volatile permissions; Skip
records cleanup as pending.

## Separately authorised stored-cap experiment

`XboxStoredCapExperiment.java` uses the same USB connection and native dialogs.
It requires explicit authorisation to write EEPROM record 0, which also contains
controller identity data. The candidate cap value comes from the newer firmware;
its effect on the installed firmware remains unverified.

```sh
mise exec java@corretto-25 -- jbang tools/XboxStoredCapExperiment.java --self-test
mise exec java@corretto-25 -- jbang tools/XboxStoredCapExperiment.java --run /dev/cu.usbmodem1101
```

The runner requires two matching reads of record 0 with length 26 and candidate
cap 80. It saves the original 32-byte slot, a trial slot and a recovery command
with mode `0600`. Only payload byte 22 changes to 5 in the trial; its stored
checksum changes accordingly. Identity and padding bytes remain identical.

The Windows driver's `DccUsbFwWriteEEProm` supplies this write protocol:

| Operation            | bmRequestType | bRequest | wValue | wIndex | Payload                              |
| -------------------- | ------------- | -------- | ------ | ------ | ------------------------------------ |
| Record-0 write       | `40`          | `10`     | `030e` | `0306` | `00 1a`, then the saved 26-byte data |
| Completion and check | `c0`          | `00`     | `0008` | `ff00` | 64-byte reply, command `0x0306`      |

The controller generates the checksum. The runner requires a matching successful
completion and two reads matching the entire intended slot. First it attempts
to write the original data unchanged. Failure stops the trial and triggers two
reads to check that the original is intact. It does not continue to cap 5.

If that baseline succeeds, dialogs guide a Bluetooth baseline, the cap-5 write,
a reboot, observation through the XIAO, and restoration of the original record.
The restoration phase runs after cancellation or failure once the cap write
has been attempted. A disconnected Bluetooth observation is discarded. If the
process is killed or restoration cannot finish, use the saved command:

```sh
mise exec java@corretto-25 -- jbang tools/XboxStoredCapExperiment.java --restore target/campaigns/<run>/cap-original.bin
```

Restoration accepts only a controller whose full slot matches the original or
trial snapshot. An interrupted or failed EEPROM write can corrupt persistent
data; a backup does not remove that risk. The physical baseline below did not
establish a working write or restore operation.

## Evidence

Each run writes `target/campaigns/<UTC timestamp>/campaign.log`. A verified
record read also writes `record-0-slot.bin` there. These files use mode `0600`;
the record can contain controller identity data. The existing XIAO serial layer
also writes its full transcript under `target/xiao/runs/`.

<details>
<summary>Earlier physical trials on firmware 5.9.2709.0</summary>

### Recorded physical results, 2026-10-24

Run `2026-10-24T14-55-17.061091Z` connected through the XIAO, established
encryption (`SECURITY 1 2 16`), read firmware `5.9.2709.0`, and verified fresh
input notifications. The observation dialogs recorded:

| Trial                        | Transport result                                                     | User observation           |
| ---------------------------- | -------------------------------------------------------------------- | -------------------------- |
| BLE raw factory LED          | Four writes and step-20 cleanup acknowledged                         | Unchanged                  |
| BLE GIP factory LED          | Four writes and cleanup acknowledged                                 | Unchanged                  |
| BLE raw factory unlock + LED | Three keys, four LED writes and cleanup acknowledged                 | Unchanged                  |
| BLE GIP factory unlock + LED | Three keys, four LED writes and cleanup acknowledged                 | Unchanged                  |
| USB shared HID baseline      | Four brightness writes accepted                                      | Changed                    |
| USB factory unlock           | All three OUT transfers complete; matching successful `0x0211` reply | No visual effect expected  |
| USB factory LED              | Four complete requests; matching successful `0x0b01` replies         | Changed                    |
| Record-0 lookup              | Successful `0x0308` reply, payload `60 00`                           | Legacy address-only layout |

The initial parser required three metadata bytes and stopped before raw reads.
USB brightness step 20 was restored, and the user confirmed a power cycle.
Run `2026-10-24T15-06-39.110669Z` resumes only USB reads with both known
metadata layouts accepted. It completed the unlock, lookup and two raw reads.
The first poll returned a four-byte busy response; the next poll returned a
successful `0x0300` reply with 32 data bytes. Both reads were identical.
Record ID 0, payload length 26 and the additive checksum passed validation.
Payload byte 22 was 80. The snapshot is `record-0-slot.bin` in that run's
directory, 32 bytes, mode `0600`. The user confirmed the final power cycle,
and the runner exited with status 0.

This establishes a working read protocol and a candidate cap value on installed
firmware. The relationship between that byte and brightness remains supported
by analysis of the newer image.

Run `2026-10-24T15-25-22.253485Z` attempted `--usb-image`. After the successful
unlock, vendor OUT `40 10 160c 1601`, payload `00 70 02 10 10`, requested 16
bytes at `0x10027000`. The transfer completed, but reply polling continued to
return the old `0x0211` response for 2.5 seconds. No matching `0x1601` reply
arrived, so no vector or application file was saved. The user confirmed the
final power cycle. No memory write was sent.

Run `2026-10-24T15-35-52.319301Z` attempted the explicitly authorised stored-cap
experiment. Its evidence is in `stored-cap.log`. The original and trial files
were saved privately, and the native review dialog was accepted. The baseline
OUT `40 10 030e 0306` transferred all 28 bytes of the unchanged record. Polling
kept returning the preceding `0x0300` read reply, with no matching `0x0306`
completion. Two subsequent full-slot reads matched the original byte for byte,
including cap 80 and checksum. The runner recorded `BASELINE_WRITER_UNAVAILABLE`,
stopped before cap 5, and recorded the user's final power-cycle confirmation.
It exited with status 1 to report the unavailable write route. No restoration
write was needed because the original remained intact.

Both missing completions are consistent with the analysed newer firmware's
level-1 whitelist, which omits `0x1601` and `0x0306`. They do not establish the
exact permission implementation of installed `5.9.2709.0`. No changed cap,
successful EEPROM write, verified restore or resulting BLE dimming was observed.

The earlier `2026-10-24T14-38-59.228196Z` run was cancelled at the opening
dialog, before hardware commands. No observation is inferred from an
unanswered dialog. ATT acknowledgements do not establish a BLE factory unlock.

</details>

## Verification

Self-tests check exact packet bytes, brightness bounds, command exclusions,
reply correlation and lengths, and rejection of a corrupt EEPROM checksum.
The stored-cap self-test checks the single cap-byte change, checksum adjustment,
exact original write payload, and rejection of unknown identity data or cap values.
Physical results are recorded separately from these checks.
