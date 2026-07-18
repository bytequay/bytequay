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
package com.bytequay.app.service.workspaces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Reads ByteQuay's provider-neutral WORKSPACE.md with a hard context bound. */
public final class WorkspaceDocumentLoader
{
    public static final String FILE_NAME = "WORKSPACE.md";
    public static final int MAX_CHARS = 16_384;

    private WorkspaceDocumentLoader() {}

    public static String load(String workingDir)
    {
        if (workingDir == null || workingDir.isBlank()) {
            return "";
        }
        Path root;
        try {
            root = Path.of(workingDir).toAbsolutePath().normalize();
        }
        catch (RuntimeException e) {
            return "";
        }
        Path document = root.resolve(FILE_NAME).normalize();
        if (!document.startsWith(root)
                || !Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try (var reader = Files.newBufferedReader(document, StandardCharsets.UTF_8)) {
            char[] buffer = new char[MAX_CHARS + 1];
            int count = 0;
            while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read < 0) {
                    break;
                }
                count += read;
            }
            if (count <= MAX_CHARS) {
                return new String(buffer, 0, count).strip();
            }
            return new String(buffer, 0, MAX_CHARS).stripTrailing()
                    + "\n\n[WORKSPACE.md truncated by ByteQuay]";
        }
        catch (IOException e) {
            return "";
        }
    }
}
