// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use hidapi::{BusType, DeviceInfo, HidApi, HidDevice, MAX_REPORT_DESCRIPTOR_SIZE};
use std::env;
use std::error::Error;
use std::ffi::{CStr, CString, OsString};
use std::io::{self, Write};
use std::process::ExitCode;
use std::time::{Duration, Instant};

const HELP: &str = "xbox-light - inspect Xbox Wireless Controllers over Bluetooth on macOS

Usage:
  xbox-light list
  xbox-light info [--device PATH]
  xbox-light diagnose [--device PATH]
  xbox-light --help

list      Show connected Xbox Bluetooth gamepad interfaces and their paths.
info      Read identity and listen for battery information for up to 3 seconds.
diagnose  Print the HID descriptor and capture input reports for 5 seconds.

Use --device with a path from list when multiple interfaces are present.
LED control and LED/firmware readback are not implemented for Bluetooth.
";

#[derive(Debug, PartialEq, Eq)]
enum Command {
    Help,
    List,
    Info,
    Diagnose,
}

#[derive(Debug, PartialEq, Eq)]
struct Args {
    command: Command,
    device: Option<CString>,
}

fn parse_args(mut args: impl Iterator<Item = OsString>) -> Result<Args, String> {
    let first = args.next();
    let command = match first.as_deref() {
        None => Command::Help,
        Some(arg) if arg == "-h" || arg == "--help" => Command::Help,
        Some(arg) if arg == "list" => Command::List,
        Some(arg) if arg == "info" => Command::Info,
        Some(arg) if arg == "diagnose" => Command::Diagnose,
        Some(arg) => return Err(format!("Unknown command: {}", arg.to_string_lossy())),
    };
    let mut device = None;
    while let Some(arg) = args.next() {
        if arg == "--device" && matches!(command, Command::Info | Command::Diagnose) {
            if device.is_some() {
                return Err("--device may only be supplied once".into());
            }
            let path = args.next().ok_or("--device requires a path from list")?;
            let bytes = path.as_encoded_bytes();
            if bytes.is_empty() || bytes.starts_with(b"-") {
                return Err("--device requires a path from list".into());
            }
            device = Some(CString::new(bytes).map_err(|_| "Device path contains a NUL byte")?);
        } else if arg == "-h" || arg == "--help" {
            return Ok(Args {
                command: Command::Help,
                device: None,
            });
        } else {
            return Err(format!("Unexpected argument: {}", arg.to_string_lossy()));
        }
    }
    Ok(Args { command, device })
}

fn is_xbox_bluetooth_gamepad(
    vendor: u16,
    product: u16,
    bus: BusType,
    usage_page: u16,
    usage: u16,
) -> bool {
    // Xbox Bluetooth/BLE product IDs from SDL's src/joystick/usb_ids.h.
    vendor == 0x045e
        && matches!(
            product,
            0x02e0 | 0x02fd | 0x0b05 | 0x0b0c | 0x0b13 | 0x0b20 | 0x0b21 | 0x0b22
        )
        && matches!(bus, BusType::Bluetooth)
        && usage_page == 0x01
        && matches!(usage, 0x04 | 0x05)
}

fn select_path<'a>(
    mut paths: impl Iterator<Item = &'a CStr>,
    requested: Option<&CStr>,
) -> Result<&'a CStr, String> {
    if let Some(requested) = requested {
        return paths.find(|path| *path == requested).ok_or_else(|| {
            "Requested interface is not a connected supported Xbox Bluetooth gamepad; run xbox-light list"
                .into()
        });
    }
    let first = paths.next().ok_or(
        "No supported Xbox Bluetooth controller found. Pair and connect it in macOS Bluetooth settings, then run xbox-light list",
    )?;
    if paths.next().is_some() {
        return Err("Multiple Xbox gamepad interfaces found; run xbox-light list and select one with --device PATH".into());
    }
    Ok(first)
}

fn print_identity(out: &mut impl Write, device: &DeviceInfo) -> io::Result<()> {
    writeln!(
        out,
        "Name: {}",
        device.product_string().unwrap_or("unavailable")
    )?;
    writeln!(
        out,
        "Manufacturer: {}",
        device.manufacturer_string().unwrap_or("unavailable")
    )?;
    writeln!(
        out,
        "Vendor/product: {:04x}:{:04x}",
        device.vendor_id(),
        device.product_id()
    )?;
    writeln!(out, "Transport: {:?}", device.bus_type())?;
    writeln!(out, "Path: {}", device.path().to_string_lossy())?;
    writeln!(
        out,
        "Serial: {}",
        device
            .serial_number()
            .filter(|serial| !serial.is_empty())
            .unwrap_or("unavailable")
    )?;
    writeln!(
        out,
        "HID usage: {:04x}:{:04x}",
        device.usage_page(),
        device.usage()
    )?;
    writeln!(
        out,
        "Device release number: 0x{:04x} (not a firmware version)",
        device.release_number()
    )?;
    writeln!(out, "Firmware: unavailable (not exposed by HIDAPI)")?;
    writeln!(
        out,
        "LED state: unavailable (no verified Bluetooth read mechanism)"
    )?;
    writeln!(out, "LED control: unsupported by this tool over Bluetooth")
}

fn battery_status(report: &[u8]) -> Option<&'static str> {
    // SDL's Xbox Bluetooth driver: report 0x04, flags in byte 1.
    // Bits 0-1 give a coarse level; bits 2-3 equal zero for USB power.
    let [0x04, flags] = report else {
        return None;
    };
    if flags & 0x0c == 0 {
        return Some("USB power (battery level and charging state unavailable)");
    }
    Some(match flags & 0x03 {
        0 => "low (coarse level 0/3), battery power",
        1 => "medium (coarse level 1/3), battery power",
        2 => "high (coarse level 2/3), battery power",
        _ => "full (coarse level 3/3), battery power",
    })
}

fn print_hex(out: &mut impl Write, bytes: &[u8]) -> io::Result<()> {
    for (index, byte) in bytes.iter().enumerate() {
        if index > 0 {
            write!(out, " ")?;
        }
        write!(out, "{byte:02x}")?;
    }
    writeln!(out)
}

fn capture(out: &mut impl Write, device: &HidDevice, diagnose: bool) -> Result<(), Box<dyn Error>> {
    let duration = Duration::from_secs(if diagnose { 5 } else { 3 });
    let start = Instant::now();
    let mut report = [0; MAX_REPORT_DESCRIPTOR_SIZE];
    let mut battery = None;
    let mut count = 0;
    // Use one deadline even if the controller continuously produces input.
    while let Some(remaining) = duration.checked_sub(start.elapsed()) {
        if remaining.is_zero() {
            break;
        }
        let timeout_ms = remaining.as_millis().clamp(1, 100) as i32;
        let size = device
            .read_timeout(&mut report, timeout_ms)
            .map_err(|error| {
                format!("Cannot read controller input (it may have disconnected): {error}")
            })?;
        if size == 0 {
            continue;
        }
        count += 1;
        if diagnose {
            write!(
                out,
                "Input +{}ms ({size} bytes): ",
                start.elapsed().as_millis()
            )?;
            print_hex(out, &report[..size])?;
        }
        if let Some(status) = battery_status(&report[..size]) {
            battery = Some(status);
            if !diagnose {
                break;
            }
        }
    }
    writeln!(
        out,
        "Battery: {}",
        battery.unwrap_or("unavailable (no recognized battery report received)")
    )?;
    if diagnose {
        writeln!(out, "Captured input reports: {count}")?;
    }
    Ok(())
}

fn run(args: Args, out: &mut impl Write) -> Result<(), Box<dyn Error>> {
    if args.command == Command::Help {
        write!(out, "{HELP}")?;
        return Ok(());
    }
    let api = HidApi::new().map_err(|error| format!("Cannot enumerate HID devices: {error}"))?;
    let devices: Vec<_> = api
        .device_list()
        .filter(|device| {
            is_xbox_bluetooth_gamepad(
                device.vendor_id(),
                device.product_id(),
                device.bus_type(),
                device.usage_page(),
                device.usage(),
            )
        })
        .collect();
    if args.command == Command::List {
        if devices.is_empty() {
            writeln!(
                out,
                "No supported Xbox Bluetooth controllers found. Pair and connect one in macOS Bluetooth settings."
            )?;
        }
        for (index, device) in devices.iter().enumerate() {
            if index > 0 {
                writeln!(out)?;
            }
            print_identity(out, device)?;
        }
        return Ok(());
    }
    let path = select_path(
        devices.iter().map(|device| device.path()),
        args.device.as_deref(),
    )?;
    let info = devices.iter().find(|device| device.path() == path).unwrap();
    print_identity(out, info)?;
    out.flush()?;
    let device = api.open_path(path).map_err(|error| {
        format!("Cannot open controller with shared access: {error}. Check that it is still connected and review macOS privacy permissions if access was denied")
    })?;
    if args.command == Command::Diagnose {
        let mut descriptor = [0; MAX_REPORT_DESCRIPTOR_SIZE];
        let size = device
            .get_report_descriptor(&mut descriptor)
            .map_err(|error| {
                format!("Cannot read the controller HID report descriptor: {error}")
            })?;
        write!(out, "HID report descriptor ({size} bytes): ")?;
        print_hex(out, &descriptor[..size])?;
        writeln!(
            out,
            "Capturing input for 5 seconds; move sticks or press buttons to generate reports."
        )?;
        out.flush()?;
    }
    capture(out, &device, args.command == Command::Diagnose)
}

fn main() -> ExitCode {
    let args = match parse_args(env::args_os().skip(1)) {
        Ok(args) => args,
        Err(error) => {
            eprintln!("xbox-light: {error}\nRun xbox-light --help for usage.");
            return ExitCode::from(2);
        }
    };
    let mut out = io::stdout().lock();
    match run(args, &mut out) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            if error
                .downcast_ref::<io::Error>()
                .is_some_and(|error| error.kind() == io::ErrorKind::BrokenPipe)
            {
                return ExitCode::SUCCESS;
            }
            eprintln!("xbox-light: {error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(values: &[&str]) -> Result<Args, String> {
        parse_args(values.iter().map(OsString::from))
    }

    #[test]
    fn parses_commands_and_device_paths() {
        assert_eq!(args(&[]).unwrap().command, Command::Help);
        assert_eq!(args(&["--help"]).unwrap().command, Command::Help);
        assert_eq!(args(&["info", "--help"]).unwrap().command, Command::Help);
        assert_eq!(args(&["list"]).unwrap().command, Command::List);
        assert_eq!(args(&["diagnose"]).unwrap().command, Command::Diagnose);
        assert_eq!(
            args(&["info", "--device", "DevSrvsID:123"]).unwrap(),
            Args {
                command: Command::Info,
                device: Some(c"DevSrvsID:123".to_owned()),
            }
        );
    }

    #[test]
    fn rejects_invalid_arguments() {
        for values in [
            vec!["brightness", "1"],
            vec!["info", "--device"],
            vec!["info", "--device", ""],
            vec!["info", "--device", "--help"],
            vec!["info", "--device", "a", "--device", "b"],
            vec!["info", "--device", "a\0b"],
            vec!["list", "--device", "a"],
            vec!["diagnose", "extra"],
            vec!["info", "--unknown"],
        ] {
            assert!(args(&values).is_err(), "{values:?}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn rejects_non_utf8_commands_and_preserves_device_path_bytes() {
        use std::os::unix::ffi::OsStringExt;

        let raw = OsString::from_vec(vec![0xff]);
        assert!(parse_args([raw.clone()].into_iter()).is_err());
        let parsed =
            parse_args([OsString::from("info"), OsString::from("--device"), raw].into_iter())
                .unwrap();
        assert_eq!(parsed.device.unwrap().as_bytes(), &[0xff]);
    }

    #[test]
    fn selects_only_unambiguous_or_requested_paths() {
        assert!(select_path([].into_iter(), None).is_err());
        assert_eq!(select_path([c"a"].into_iter(), None).unwrap(), c"a");
        assert!(select_path([c"a", c"b"].into_iter(), None).is_err());
        assert_eq!(
            select_path([c"a", c"b"].into_iter(), Some(c"b")).unwrap(),
            c"b"
        );
        assert!(select_path([c"a"].into_iter(), Some(c"b")).is_err());
        assert!(select_path([].into_iter(), Some(c"b")).is_err());
    }

    #[test]
    fn filters_out_other_devices_and_transports() {
        for product in [
            0x02e0, 0x02fd, 0x0b05, 0x0b0c, 0x0b13, 0x0b20, 0x0b21, 0x0b22,
        ] {
            assert!(is_xbox_bluetooth_gamepad(
                0x045e,
                product,
                BusType::Bluetooth,
                1,
                5
            ));
        }
        assert!(!is_xbox_bluetooth_gamepad(
            0x1234,
            0x0b13,
            BusType::Bluetooth,
            1,
            5
        ));
        assert!(!is_xbox_bluetooth_gamepad(
            0x045e,
            0x1234,
            BusType::Bluetooth,
            1,
            5
        ));
        assert!(!is_xbox_bluetooth_gamepad(
            0x045e,
            0x0b13,
            BusType::Usb,
            1,
            5
        ));
        assert!(!is_xbox_bluetooth_gamepad(
            0x045e,
            0x0b13,
            BusType::Unknown,
            1,
            5
        ));
        assert!(!is_xbox_bluetooth_gamepad(
            0x045e,
            0x0b13,
            BusType::Bluetooth,
            1,
            6
        ));
        assert!(!is_xbox_bluetooth_gamepad(
            0x045e,
            0x0b13,
            BusType::Bluetooth,
            0xff00,
            5
        ));
    }

    #[test]
    fn decodes_only_recognized_battery_reports() {
        assert!(battery_status(&[0x04, 0x04]).unwrap().starts_with("low"));
        assert!(battery_status(&[0x04, 0x05]).unwrap().starts_with("medium"));
        assert!(battery_status(&[0x04, 0x06]).unwrap().starts_with("high"));
        assert!(battery_status(&[0x04, 0x07]).unwrap().starts_with("full"));
        assert!(
            battery_status(&[0x04, 0x03])
                .unwrap()
                .starts_with("USB power")
        );
        assert_eq!(battery_status(&[0x04, 0x86]), battery_status(&[0x04, 0x06]));
        for report in [&[][..], &[0x04], &[0x01, 0x04], &[0x04, 0x04, 0x00]] {
            assert_eq!(battery_status(report), None);
        }
    }
}
