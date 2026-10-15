// SPDX-License-Identifier: MPL-2.0
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.nio.file.*;
import java.nio.file.attribute.*;

class ProtectXboxCaptures {
    public static void main(String[] args) throws Exception {
        for (String name : args) {
            Path capture = Path.of(name);
            var modified = Files.getLastModifiedTime(capture);
            Path temporary = Files.createTempFile(capture.toAbsolutePath().getParent(), ".capture-private-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.write(temporary, Files.readAllBytes(capture));
                if (Files.mismatch(capture, temporary) != -1) throw new IllegalStateException("Copy differs: " + capture);
                Files.setLastModifiedTime(temporary, modified);
                Files.move(temporary, capture, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!Files.getPosixFilePermissions(capture).equals(PosixFilePermissions.fromString("rw-------")))
                    throw new IllegalStateException("Unexpected permissions: " + capture);
                System.out.println("Protected byte-identical capture: " + capture);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
