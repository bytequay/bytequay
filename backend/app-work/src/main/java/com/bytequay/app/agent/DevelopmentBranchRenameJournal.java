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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.TaskId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;

/** One durable intent outside the checkout closes the Git-ref/task-row crash window. */
@Component
public class DevelopmentBranchRenameJournal
{
    private final AgentResourceService resources;
    private final ObjectMapper mapper;

    public DevelopmentBranchRenameJournal(AgentResourceService resources, ObjectMapper mapper)
    {
        this.resources = requireNonNull(resources, "resources is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public Optional<DevelopmentBranchRename> read(TaskId taskId)
    {
        Path path = path(taskId);
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        checkState(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path), "Branch rename journal is not an owned file");
        try {
            checkState(Files.size(path) <= 4096, "Branch rename journal is too large");
            return Optional.of(mapper.readValue(Files.readAllBytes(path), DevelopmentBranchRename.class));
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot read the pending branch rename", failure);
        }
    }

    public void write(TaskId taskId, DevelopmentBranchRename rename)
    {
        Path path = path(taskId);
        checkState(!Files.exists(path, NOFOLLOW_LINKS), "An earlier branch rename still needs recovery");
        try {
            Path temporary = Files.createTempFile(path.getParent(), "branch-rename-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (FileChannel channel = FileChannel.open(temporary, WRITE)) {
                    ByteBuffer bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(rename));
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    channel.force(true);
                }
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
                forceDirectory(path.getParent());
            }
            finally {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot record the pending branch rename", failure);
        }
    }

    public void clear(TaskId taskId)
    {
        Path path = path(taskId);
        try {
            Files.deleteIfExists(path);
            forceDirectory(path.getParent());
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot finish recording the branch rename", failure);
        }
    }

    private Path path(TaskId taskId)
    {
        return resources.require(taskId).runtimeDirectory().resolve("branch-rename.json");
    }

    private static void forceDirectory(Path directory)
            throws IOException
    {
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        }
    }
}
