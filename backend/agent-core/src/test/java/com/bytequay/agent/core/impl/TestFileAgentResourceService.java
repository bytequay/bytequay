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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFileAgentResourceService
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void allocatesOwnerOnlyResourcesIdempotently()
            throws IOException
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        TaskId taskId = TaskId.random();

        TaskResources resources = service.allocate(taskId);

        assertThat(service.allocate(taskId)).isEqualTo(resources);
        assertThat(resources.runtimeDirectory()).isDirectory();
        assertThat(resources.eventsFile()).isRegularFile();
        assertThat(resources.blobsDirectory()).isDirectory();
        assertThat(resources.providerDirectory()).isDirectory();
        if (Files.getFileAttributeView(resources.runtimeDirectory(), PosixFileAttributeView.class) != null) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(resources.runtimeDirectory())))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(resources.eventsFile())))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void rejectsSymbolicLinkTaskDirectory()
            throws IOException
    {
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("runtime"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        TaskId taskId = TaskId.random();
        Files.createSymbolicLink(runtime.resolve(taskId.toString()), outside);

        FileAgentResourceService service = new FileAgentResourceService(runtime);

        assertThatThrownBy(() -> service.allocate(taskId))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void deletesLinksWithoutFollowingThem()
            throws IOException
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        TaskId taskId = TaskId.random();
        TaskResources resources = service.allocate(taskId);
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "keep");
        Files.createSymbolicLink(resources.blobsDirectory().resolve("link"), outside);

        service.delete(taskId);

        assertThat(resources.runtimeDirectory()).doesNotExist();
        assertThat(outside).hasContent("keep");
        service.delete(taskId);
    }

    @Test
    void rejectsSymbolicLinkInResourcePath()
            throws IOException
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        TaskId taskId = TaskId.random();
        TaskResources resources = service.allocate(taskId);
        Files.delete(resources.providerDirectory());
        Files.delete(resources.providerDirectory().getParent());
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-provider"));
        Files.createSymbolicLink(resources.providerDirectory().getParent(), outside);

        assertThatThrownBy(() -> service.allocate(taskId))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("symbolic link");
        assertThat(outside.resolve("scratch")).doesNotExist();
    }

    @Test
    void requiresPreviouslyAllocatedResources()
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));

        assertThatThrownBy(() -> service.require(TaskId.random()))
                .isInstanceOf(AgentStorageException.class);
    }
}
