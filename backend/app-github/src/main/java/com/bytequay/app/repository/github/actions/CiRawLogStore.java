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
package com.bytequay.app.repository.github.actions;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.TaskId;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

/** Complete, credential-redacted logs owned by the task's disposable worktree cache. */
@Component
public class CiRawLogStore
{
    private final AgentResourceService resources;
    private final Striped<Lock> locks = Striped.lock(64);

    public CiRawLogStore(AgentResourceService resources)
    {
        this.resources = requireNonNull(resources, "resources is null");
    }

    public static String id(ReadCiLogQuery query)
    {
        return Hashing.sha256().hashString(query.pullRequest().repoFullName() + "/" + query.workflowRun().id() + "/" + query.workflowRun().attempt()
                + "/" + query.workflowRun().headSha() + "/" + query.job().id() + "/" + query.job().attempt(), UTF_8).toString();
    }

    /** Publishes only a complete download. An unavailable or interrupted attempt leaves no cached file. */
    public Optional<String> cache(ReadCiLogQuery query, Function<Path, Optional<String>> download)
    {
        String id = id(query);
        Lock lock = locks.get(query.taskId().toString() + id);
        return withLock(lock, () -> {
            try {
                Path target = path(query.taskId(), id);
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    checkState(Files.isRegularFile(target, NOFOLLOW_LINKS), "CI log cache entry is not a regular file");
                    return Optional.empty();
                }
                Files.createDirectories(target.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path temporary = Files.createTempFile(target.getParent(), ".download-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    Optional<String> unavailable = download.apply(temporary);
                    if (unavailable.isEmpty()) {
                        Files.move(temporary, target, ATOMIC_MOVE);
                    }
                    return unavailable;
                }
                finally {
                    Files.deleteIfExists(temporary);
                }
            }
            catch (IOException failure) {
                throw new AgentStorageException("Cannot cache CI log", failure);
            }
        });
    }

    public String read(TaskId taskId, String logId)
    {
        try {
            return Files.readString(existing(taskId, logId), UTF_8);
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot read cached CI log", failure);
        }
    }

    /** Offsets are bytes in the cached UTF-8 file, and pages never split a code point. */
    public CiRawLogPage page(TaskId taskId, String logId, long offset, int maximumBytes)
    {
        checkArgument(offset >= 0, "offset must not be negative");
        checkArgument(maximumBytes >= 4 && maximumBytes <= 32 * 1024, "page size is outside the log limit");
        try (var channel = Files.newByteChannel(existing(taskId, logId), READ, NOFOLLOW_LINKS)) {
            long total = channel.size();
            checkArgument(offset <= total, "offset is past the end of the cached log");
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(maximumBytes + 4);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            byte[] bytes = buffer.array();
            int count = buffer.position();
            checkArgument(count == 0 || (bytes[0] & 0xC0) != 0x80, "offset must be a UTF-8 boundary returned by the tool");
            int end = Math.min(count, maximumBytes);
            while (end < count && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            return new CiRawLogPage(new String(bytes, 0, end, UTF_8), offset + end, total);
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot read cached CI log", failure);
        }
    }

    private Path existing(TaskId taskId, String logId)
    {
        Path path = path(taskId, logId);
        checkState(Files.isRegularFile(path, NOFOLLOW_LINKS), "No cached raw log; request the error log first");
        return path;
    }

    private Path path(TaskId taskId, String logId)
    {
        checkArgument(logId != null && logId.matches("[0-9a-f]{64}"), "logId must be an identifier returned by the error-log tool");
        Path directory = resources.require(taskId).worktreeCacheDirectory();
        checkState(!Files.isSymbolicLink(directory), "CI log cache directory is a symbolic link");
        Path path = directory.resolve(logId + ".log");
        checkState(!Files.isSymbolicLink(path), "CI log cache entry is a symbolic link");
        return path;
    }
}
