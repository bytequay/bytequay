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

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists a chat composer's pasted images to disk so an agent can open them
 * later — {@code ~/Library/Application Support/ByteQuay/attachments/
 * {threadId}/}, the same persistent-app-data convention {@code
 * LocalRepoService}/{@code CredentialCipher} use, not a temp dir: a
 * screenshot referenced from message history has to survive an app restart
 * just like the text around it does.
 */
@Service
public class ChatAttachmentStore
{
    /** {@code data:image/png;base64,iVBORw0...} — the shape a browser
     *  FileReader.readAsDataURL()/clipboard paste produces client-side. */
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpeg|jpg|gif|webp);base64,(.+)$");

    /** Save each data-URL to disk, returning the absolute path it landed at,
     *  in the same order. 400s on anything that isn't an image data URL. */
    public List<String> save(String threadId, List<String> dataUrls)
    {
        if (dataUrls == null || dataUrls.isEmpty()) {
            return List.of();
        }
        Path dir = attachmentsDir(threadId);
        try {
            Files.createDirectories(dir);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), "could not create attachments dir: " + e.getMessage());
        }
        List<String> paths = new ArrayList<>();
        for (String dataUrl : dataUrls) {
            paths.add(saveOne(dir, dataUrl));
        }
        return paths;
    }

    private String saveOne(Path dir, String dataUrl)
    {
        Matcher m = dataUrl == null ? null : DATA_URL.matcher(dataUrl);
        if (m == null || !m.matches()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "not a supported image data URL");
        }
        String ext = EXTENSIONS.getOrDefault(m.group(1), "bin");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(m.group(2));
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "malformed base64 image data");
        }
        Path file = dir.resolve(UUID.randomUUID() + "." + ext);
        try {
            Files.write(file, bytes);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), "could not save image: " + e.getMessage());
        }
        return file.toString();
    }

    private static final Map<String, String> EXTENSIONS = Map.of(
            "png", "png", "jpeg", "jpg", "jpg", "jpg", "gif", "gif", "webp", "webp");

    /** A saved attachment's bytes + guessed content type, for serving back
     *  to the frontend so a message's thumbnail can actually render. */
    public record Attachment(byte[] bytes, String mimeType)
    {
    }

    /** Reads a previously-saved attachment back, refusing anything outside
     *  the attachments root — a crafted {@code path} shouldn't be able to
     *  read arbitrary files off disk, even though this is a trusted
     *  local-only sidecar with no other auth. Scoped to the whole root
     *  rather than one thread's own subfolder: a brain-thread message's
     *  images are saved under the brain thread's id (see
     *  {@code BrainServiceImpl}), which the frontend never learns — it only
     *  ever knows the dev thread / task id when asking for a thumbnail. */
    public Attachment read(String path)
    {
        Path root = attachmentsRoot().normalize();
        Path requested = Path.of(path).normalize();
        if (!requested.startsWith(root)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(403), "path is outside the attachments root");
        }
        if (!Files.isRegularFile(requested)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404), "attachment not found");
        }
        try {
            return new Attachment(Files.readAllBytes(requested), MessageAttachments.mimeTypeFor(path));
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(500), "could not read attachment: " + e.getMessage());
        }
    }

    private static Path attachmentsDir(String threadId)
    {
        return attachmentsRoot().resolve(threadId);
    }

    /** Package-visible so provider adapters can grant read-only agents access
     *  to the same managed root without duplicating its location. */
    static Path attachmentsRoot()
    {
        String home = System.getProperty("user.home");
        return Path.of(home, "Library", "Application Support", "ByteQuay", "attachments");
    }
}
