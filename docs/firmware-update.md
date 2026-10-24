# Firmware inspection and update

[Findings](analysis.md) | [Firmware analysis](firmware.md) | [Windows UFU protocol](windows-drivers.md#ufu-firmware-update-checks)

On 2026-10-24, [xboxctl](https://github.com/bb441db/xboxctl) was used on macOS
to inspect and update the MERL controller (`045e:0b12`) over USB. The application
changed from `5.9.2709.0` to `5.23.6.0`. Subsequent XIAO discovery confirmed
`5.23.6.0` over GATT. The [post-update campaign](brightness-campaign.md#post-update-results-52360)
found no brightness change for seven BLE LED formats or HID Suspend/Resume;
both USB LED paths worked.

## Recorded update result

The post-update `xboxctl info` output reports:

| Component                   | Version after update    | Reported address | Reported partition size |
| --------------------------- | ----------------------- | ---------------- | ----------------------- |
| 1, bootloader               | `5.9.2709.0`, unchanged | `0x10000000`     | `0x5000`                |
| 2                           | `5.23.6.0`              | `0x10005000`     | `0x22000`               |
| 3, active application       | `5.23.6.0`              | `0x10027000`     | `0x7e000`               |
| 4, second application image | `5.23.330.0`            | `0x10027000`     | `0x7e000`               |

UFU protocol: 2. Flags: `0x00`. Maximum write: `0x1000` bytes. The updated
firmware supplies partition metadata that was absent from the earlier replies.
Components 3 and 4 report the same address; their version stamps do not establish
that both images coexist in flash. No installed flash dump or byte comparison
was obtained.

The readback is preserved locally at
`target/research/firmware/post-update-info-2026-10-24.txt`, mode `0600`.

## Available methods

Microsoft's [official update procedure](https://support.xbox.com/en-US/help/hardware-network/controller/update-xbox-wireless-controller)
uses Xbox Accessories on Xbox or Windows. On Windows, updates require USB or
the Xbox Wireless Adapter, as clarified by
[Microsoft support](https://answers.microsoft.com/en-us/xbox/forum/all/is-there-a-software-update-for-the-wireless/d71f5426-4476-48d1-be45-7418b5b97ec2).
No official native macOS or Linux method was identified in this investigation.

xboxctl provides native USB inspection and updates for macOS and Linux.
This investigation used its `info` and `update` commands on macOS. Linux
flashing was not tested. Installation instructions are in the
[upstream documentation](https://github.com/bb441db/xboxctl#installation).
The XIAO is used for Bluetooth experiments and is unnecessary for updating.

## Download the analysed package

Run from the repository root, with Java 25 and JBang:

```sh
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java
mise exec java@corretto-25 -- jbang tools/FetchXboxFirmware.java --help
```

Output: `target/firmware/MERL-5.23.6.0/package.bin`.

Expected SHA-256:
`8c2fb2ee745646d4021d00b28b6aefeb1b50a608a433a246d2bea5604dc0e693`.

The downloader verifies the pinned hash, model identity, version and component
bounds. Existing files are verified before reuse; a cached package works
offline. `--help` includes the Microsoft URL and manual download command.
The [package reference](firmware.md#download-and-package-identity) records the
feed URLs and component layout. Use the complete `rdhm` package for updating.
Embedded Microsoft signatures are not verified by this project.

## Inspect and update over USB

The commands below use the binary at `target/xboxctl-build/debug/xboxctl`.
Connect the controller with a USB data cable. On macOS, commands that capture
the controller's interface require root; downloads and Java tools remain
unprivileged.

```sh
# Read controller identity and component versions.
sudo target/xboxctl-build/debug/xboxctl info

# Compare the connected controller with the downloaded package.
sudo target/xboxctl-build/debug/xboxctl update --dry-run \
  target/firmware/MERL-5.23.6.0/package.bin

# Review the proposed components, then type 'update' at the confirmation prompt.
sudo target/xboxctl-build/debug/xboxctl update \
  target/firmware/MERL-5.23.6.0/package.bin

# Verify component versions after the automatic restarts.
sudo target/xboxctl-build/debug/xboxctl info
```

Check the dry-run output for the connected controller's identity and planned
components. A package-only inspection without a controller does not establish
a device comparison. Keep the cable connected through all restarts until the
update completes and the final versions are verified.

For macOS access errors, first use the `sudo` form above. A list of processes
with open USB clients does not by itself establish exclusive ownership;
those handles may be shared.

## Validation limits

Use stable host power, prevent sleep, and use a reliable USB data cable and
port. A Windows VM must reclaim the controller after each restart; the
[xpadneo update guide](https://atar-axis.github.io/xpadneo/#firmware-update--flashing-instructions)
describes that USB reassignment requirement.

An interrupted update can leave the controller unusable. If completion cannot
be verified, preserve the log and use Xbox Support recovery guidance. A full
installed-image backup and verified rollback are unavailable here. The saved
record-0 snapshot contains configuration and identity data, rather than firmware.
