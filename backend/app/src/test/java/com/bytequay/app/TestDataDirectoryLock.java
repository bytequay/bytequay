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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TestDataDirectoryLock
{
    @TempDir
    private Path directory;

    @Test
    void refusesASecondBackendAndNamesTheHolder(CapturedOutput output)
            throws Exception
    {
        long pid = ProcessHandle.current().pid();

        assertThat(DataDirectoryLock.acquire(directory, Duration.ZERO)).isTrue();
        assertThat(Files.readString(directory.resolve("backend.lock"))).isEqualTo(Long.toString(pid));

        assertThat(DataDirectoryLock.acquire(directory, Duration.ofMillis(300))).isFalse();
        assertThat(output.getErr()).contains("pid " + pid).contains(directory.toString());
    }

    @Test
    void waitsForABackendThatIsStillShuttingDown()
            throws Exception
    {
        FileChannel previous = FileChannel.open(directory.resolve("backend.lock"), CREATE, READ, WRITE);
        FileLock lock = previous.lock();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
                lock.release();
                previous.close();
            }
            catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(DataDirectoryLock.acquire(directory, Duration.ofSeconds(10))).isTrue();
    }
}
