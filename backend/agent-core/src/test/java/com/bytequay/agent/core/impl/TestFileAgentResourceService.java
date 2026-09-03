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

import com.bytequay.agent.core.AgentAttachmentNotFoundException;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    void storesAVerifiedImageAsATaskOwnedBlobAndReadsItBack()
            throws IOException
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        TaskId taskId = TaskId.random();
        service.allocate(taskId);

        StoredAgentAttachment stored = service.storeAttachment(
                new StoreAgentAttachmentCommand(taskId, "../shots/failing test.png", "image/png", png(12, 7)));

        assertThat(stored.name()).isEqualTo("failing test.png");
        assertThat(stored.mediaType()).isEqualTo("image/png");
        assertThat(stored.width()).isEqualTo(12);
        assertThat(stored.height()).isEqualTo(7);
        assertThat(stored.path()).startsWith(temporaryDirectory.resolve("runtime").resolve(taskId.toString()));
        assertThat(stored.path()).isRegularFile();
        if (Files.getFileAttributeView(stored.path(), PosixFileAttributeView.class) != null) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored.path()))).isEqualTo("rw-------");
        }
        assertThat(service.readAttachment(taskId, stored.id())).isEqualTo(stored);
        assertThat(stored.reference().id()).isEqualTo(stored.id());
        assertThat(stored.input().path()).isEqualTo(stored.path());

        // Another task can never reach it, and a removed one reads as gone.
        assertThatThrownBy(() -> service.readAttachment(TaskId.random(), stored.id()))
                .isInstanceOf(RuntimeException.class);
        service.deleteAttachment(taskId, stored.id());
        assertThatThrownBy(() -> service.readAttachment(taskId, stored.id()))
                .isInstanceOf(AgentAttachmentNotFoundException.class);
    }

    @Test
    void rejectsWhatIsNotAnAcceptableImage()
            throws IOException
    {
        FileAgentResourceService service = new FileAgentResourceService(temporaryDirectory.resolve("runtime"), 4096, 50);
        TaskId taskId = TaskId.random();
        service.allocate(taskId);

        // A claim never decides the type; the bytes do.
        assertThatThrownBy(() -> service.storeAttachment(new StoreAgentAttachmentCommand(taskId, "a.jpg", "image/jpeg", png(2, 2))))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("claims image/jpeg");
        assertThatThrownBy(() -> service.storeAttachment(new StoreAgentAttachmentCommand(taskId, "a.png", "image/png", "not an image".getBytes())))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("not a PNG, JPEG or GIF");
        assertThatThrownBy(() -> service.storeAttachment(new StoreAgentAttachmentCommand(taskId, "big.png", "image/png", png(10, 10))))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("pixels");
        assertThatThrownBy(() -> service.storeAttachment(new StoreAgentAttachmentCommand(taskId, "huge.png", "image/png", new byte[5000])))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("bytes");
        // A reference this service never issued names no file, whatever it looks like.
        assertThatThrownBy(() -> service.readAttachment(taskId, "../../events.jsonl"))
                .isInstanceOf(AgentAttachmentNotFoundException.class);
    }

    private static byte[] png(int width, int height)
            throws IOException
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
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
