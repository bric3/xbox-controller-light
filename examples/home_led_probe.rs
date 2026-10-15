// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use hidapi::{BusType, HidApi, HidDevice};
use std::error::Error;

const USAGE: &str = "Usage: cargo run --example home_led_probe -- [--sweep [--dry-run]]";
// SDL 3.4.16: SetHomeLED requests steady brightness; normal brightness is step 20.
const LED_PACKET: [u8; 7] = [0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 10];

fn led_packet(sequence: u8, length: usize, unnumbered: bool) -> Vec<u8> {
    let offset = usize::from(unnumbered);
    let mut packet = vec![0; length + offset];
    packet[offset..offset + LED_PACKET.len()].copy_from_slice(&LED_PACKET);
    packet[offset + 2] = sequence;
    packet
}

fn sweep(device: Option<&HidDevice>) -> Result<(), Box<dyn Error>> {
    let mut attempts = 0;
    for feature in [false, true] {
        for sequence in [0, 1] {
            for length in [7, 9, 64] {
                for unnumbered in [false, true] {
                    let packet = led_packet(sequence, length, unnumbered);
                    attempts += 1;
                    println!(
                        "Candidate {attempts}: {} report, sequence={sequence}, GIP bytes={length}, leading report-ID zero={unnumbered}, data={packet:02x?}",
                        if feature { "feature" } else { "output" }
                    );
                    let Some(device) = device else { continue };
                    let result = if feature {
                        device.send_feature_report(&packet).map(|()| packet.len())
                    } else {
                        device.write(&packet)
                    };
                    match result {
                        Ok(written) if written == packet.len() => {
                            println!(
                                "HIDAPI accepted the candidate; stopping for a visual check. This is not LED readback or confirmation of a brightness change."
                            );
                            return Ok(());
                        }
                        Ok(written) => {
                            return Err(format!(
                                "Incomplete write: {written}/{} bytes; stopping",
                                packet.len()
                            )
                            .into());
                        }
                        Err(error) => {
                            println!("Rejected: {error}");
                            // Other errors may indicate a lost connection or denied access.
                            // Continue only for macOS's missing-report rejection.
                            if !error.to_string().contains("0xE00002F0") {
                                return Err(error.into());
                            }
                        }
                    }
                }
            }
        }
    }
    if device.is_some() {
        Err(
            format!("All {attempts} LED candidates were rejected; no working LED route found")
                .into(),
        )
    } else {
        println!("Dry run: {attempts} LED candidates; no device access or writes");
        Ok(())
    }
}

fn main() -> Result<(), Box<dyn Error>> {
    let args = std::env::args_os().skip(1).collect::<Vec<_>>();
    let (sweeping, dry_run) = match args.as_slice() {
        [] => (false, false),
        [flag] if flag == "--sweep" => (true, false),
        [first, second] if first == "--sweep" && second == "--dry-run" => (true, true),
        _ => return Err(USAGE.into()),
    };
    if dry_run {
        return sweep(None);
    }
    let api = HidApi::new()?;
    let mut matches = api.device_list().filter(|device| {
        device.vendor_id() == 0x045e
            && device.product_id() == 0x0b13
            && matches!(device.bus_type(), BusType::Bluetooth)
            && device.usage_page() == 0x01
            && device.usage() == 0x05
    });
    let info = matches
        .next()
        .ok_or("No Series X|S Bluetooth gamepad found")?;
    if matches.next().is_some() {
        return Err("Connect only one Series X|S Bluetooth gamepad for this probe".into());
    }
    // Bluetooth support is unverified. Do not send this to the opaque vendor GATT service.
    let device = info.open_device(&api)?;
    println!("Controller: 045e:0b13 at {}", info.path().to_string_lossy());
    if sweeping {
        // SDL's Bluetooth rumble format, with all motor strengths zero.
        // This checks output access without starting vibration.
        let control = [0x03, 0x0f, 0, 0, 0, 0, 0xff, 0, 0xeb];
        let written = device.write(&control)?;
        if written != control.len() {
            return Err(format!(
                "Incomplete rumble control write: {written}/{}",
                control.len()
            )
            .into());
        }
        println!("Zero-strength rumble control: HIDAPI accepted {written} bytes");
        return sweep(Some(&device));
    }
    println!("Sending one GIP LED command: step 10 (half SDL's normal step 20)");
    let written = device.write(&LED_PACKET)?;
    if written != LED_PACKET.len() {
        return Err(format!("Incomplete write: {written}/{} bytes", LED_PACKET.len()).into());
    }
    println!(
        "HIDAPI accepted {written} bytes. Check the X light visually; LED readback is unavailable."
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidates_preserve_the_led_command_and_brightness() {
        for sequence in [0, 1] {
            for length in [7, 9, 64] {
                for unnumbered in [false, true] {
                    let packet = led_packet(sequence, length, unnumbered);
                    let offset = usize::from(unnumbered);
                    assert_eq!(packet.len(), length + offset);
                    assert!(packet[..offset].iter().all(|byte| *byte == 0));
                    assert_eq!(
                        &packet[offset..offset + 7],
                        &[0x0a, 0x20, sequence, 0x03, 0x00, 0x01, 10]
                    );
                    assert!(packet[offset + 7..].iter().all(|byte| *byte == 0));
                }
            }
        }
    }
}
