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
package com.bytequay.app.service.threads;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

@ResourceLock(SYSTEM_PROPERTIES)
class TestChatAttachmentStore
{
    // A 1x1 transparent PNG — small enough to inline, real enough to decode.
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    private final String threadId = "test-thread-" + UUID.randomUUID();
    private final ChatAttachmentStore store = new ChatAttachmentStore();

    @TempDir
    private Path home;

    private String originalUserHome;

    @BeforeEach
    void isolateAttachments()
    {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreUserHome()
    {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        }
        else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void savesAPngDataUrlAndReturnsItsPath()
    {
        List<String> paths = store.save(threadId, List.of("data:image/png;base64," + PNG_BASE64));

        assertThat(paths).hasSize(1);
        Path saved = Path.of(paths.get(0));
        assertThat(saved).exists();
        assertThat(saved.toString()).endsWith(".png");
        assertThat(saved.toString()).contains(threadId);
    }

    @Test
    void writtenBytesMatchTheDecodedInput()
            throws IOException
    {
        List<String> paths = store.save(threadId, List.of("data:image/png;base64," + PNG_BASE64));

        byte[] written = Files.readAllBytes(Path.of(paths.get(0)));
        assertThat(written).isEqualTo(Base64.getDecoder().decode(PNG_BASE64));
    }

    @Test
    void emptyOrNullListsReturnNoPaths()
    {
        assertThat(store.save(threadId, null)).isEmpty();
        assertThat(store.save(threadId, List.of())).isEmpty();
    }

    @Test
    void rejectsANonImageDataUrl()
    {
        assertThatThrownBy(() -> store.save(threadId, List.of("data:text/plain;base64,aGVsbG8=")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsMalformedBase64()
    {
        assertThatThrownBy(() -> store.save(threadId, List.of("data:image/png;base64,not-valid-base64!!")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void savesMultipleImagesInOrder()
    {
        List<String> paths = store.save(threadId, List.of(
                "data:image/png;base64," + PNG_BASE64,
                "data:image/jpeg;base64," + PNG_BASE64));

        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).endsWith(".png");
        assertThat(paths.get(1)).endsWith(".jpg");
    }

    @Test
    void readsBackASavedAttachment()
    {
        List<String> paths = store.save(threadId, List.of("data:image/png;base64," + PNG_BASE64));

        ChatAttachmentStore.Attachment attachment = store.read(paths.get(0));

        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.bytes()).isEqualTo(Base64.getDecoder().decode(PNG_BASE64));
    }

    @Test
    void readsBackAnAttachmentSavedUnderADifferentThreadId()
    {
        // A brain-thread message's images are saved under the brain thread's
        // own id, which the frontend never learns — it only ever asks with
        // the dev thread / task id in scope. Reads are root-scoped, not
        // thread-scoped, so this must still work.
        String otherThreadId = "other-thread-" + UUID.randomUUID();
        List<String> paths = store.save(otherThreadId, List.of("data:image/png;base64," + PNG_BASE64));
        ChatAttachmentStore.Attachment attachment = store.read(paths.get(0));
        assertThat(attachment.mimeType()).isEqualTo("image/png");
    }

    @Test
    void refusesToReadOutsideTheAttachmentsRoot()
            throws IOException
    {
        Path outside = Files.createTempFile("bq-test-outside-", ".png");
        try {
            assertThatThrownBy(() -> store.read(outside.toString()))
                    .isInstanceOf(ResponseStatusException.class);
        }
        finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void missingFileIs404()
    {
        Path dir = Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "ByteQuay", "attachments", threadId);
        assertThatThrownBy(() -> store.read(dir.resolve("does-not-exist.png").toString()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
