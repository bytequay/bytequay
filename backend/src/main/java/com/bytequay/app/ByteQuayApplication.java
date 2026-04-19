/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public final class ByteQuayApplication
{
    private static final Logger log = LoggerFactory.getLogger(ByteQuayApplication.class);

    private ByteQuayApplication() {}

    public static void main(String[] args)
            throws IOException
    {
        // Create the app data directory before Spring Boot initialises the DataSource and Flyway.
        Path home = Path.of(System.getProperty("user.home"), "Library", "Application Support");
        Path dbDir = home.resolve("ByteQuay");
        // One-time migration from the old "GitTrace" data dir. If the new
        // dir doesn't exist yet but the old one does, move the whole
        // tree across so the user's encrypted PAT, SQLite DB, and sync
        // state survive the brand rename. The check is "new dir absent"
        // (not "old dir present") so a user who already has both — e.g.
        // ran an older build after the rename — doesn't get clobbered.
        Path legacyDir = home.resolve("GitTrace");
        if (!Files.exists(dbDir) && Files.exists(legacyDir)) {
            log.info("Migrating app data from {} to {}", legacyDir, dbDir);
            Files.move(legacyDir, dbDir);
        }
        Files.createDirectories(dbDir);
        log.debug("Database directory ready: {}", dbDir);

        SpringApplication.run(ByteQuayApplication.class, args);
    }
}
