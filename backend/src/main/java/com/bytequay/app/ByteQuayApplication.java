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

import com.bytequay.app.config.LegacyDatabaseGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
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
        Files.createDirectories(dbDir);
        log.debug("Database directory ready: {}", dbDir);
        LegacyDatabaseGuard.quarantinePreBaselineDatabase(dbDir.resolve("bytequay.db"));

        SpringApplication.run(ByteQuayApplication.class, args);
    }
}
