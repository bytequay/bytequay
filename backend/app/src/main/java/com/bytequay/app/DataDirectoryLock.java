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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Keeps one backend per data directory. Two JVMs on one SQLite file fail every
 * read-then-write transaction with SQLITE_BUSY, because SQLite skips the busy
 * handler when a reading transaction upgrades to a write and the configured busy
 * timeout never applies, and their sync sweeps delete each other's rows. The
 * port fallback cannot catch this: the second backend simply moves to another
 * port and keeps the same database.
 *
 * <p>The operating system releases the lock however the holder dies, SIGKILL
 * included, so a leftover lock file never blocks a restart.
 */
final class DataDirectoryLock
{
    private static final String LOCK_FILENAME = "backend.lock";
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(250);

    // The lock lasts only as long as this channel stays open, so the reference has to
    // outlive main(); a local would be collected while the backend is still serving.
    private static FileChannel held;

    private DataDirectoryLock() {}

    /**
     * Waits up to {@code wait} for the holder to let go: reopening the app right after quitting it starts this backend while the
     * previous one is still draining requests.
     */
    static boolean acquire(Path directory, Duration wait)
            throws IOException, InterruptedException
    {
        FileChannel channel = FileChannel.open(directory.resolve(LOCK_FILENAME), CREATE, READ, WRITE);
        long deadline = System.nanoTime() + wait.toNanos();
        FileLock lock = tryLock(channel);
        while (lock == null && System.nanoTime() < deadline) {
            Thread.sleep(RETRY_INTERVAL);
            lock = tryLock(channel);
        }
        if (lock == null) {
            String holder = readHolder(channel).orElse("unknown");
            // Spring has not configured logging yet, and Electron shows the backend's
            // stderr tail in its startup-failure dialog.
            System.err.printf("Another ByteQuay backend (pid %s) is already using %s.%nQuit it before starting ByteQuay again.%n", holder, directory);
            channel.close();
            return false;
        }
        writeHolder(channel);
        held = channel;
        return true;
    }

    private static FileLock tryLock(FileChannel channel)
            throws IOException
    {
        try {
            return channel.tryLock();
        }
        catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static Optional<String> readHolder(FileChannel channel)
            throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        channel.read(buffer, 0);
        String pid = new String(buffer.array(), 0, buffer.position(), UTF_8).trim();
        return pid.isEmpty() ? Optional.empty() : Optional.of(pid);
    }

    private static void writeHolder(FileChannel channel)
            throws IOException
    {
        channel.truncate(0);
        channel.write(UTF_8.encode(Long.toString(ProcessHandle.current().pid())));
        channel.force(true);
    }
}
