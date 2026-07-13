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
package com.bytequay.app.service.codegraph;

import com.bytequay.app.service.local.GitRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Defines what "fresh enough to query" means for a checkout. */
@Service
public class CodeGraphService
{
    private static final String CODEGRAPH_EXCLUDE = "/.codegraph/";
    private static final long MAX_HASH_BYTES = 1L << 20;

    private final CodeGraphRunner codeGraph;
    private final GitRunner git;

    public CodeGraphService(CodeGraphRunner codeGraph, GitRunner git)
    {
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.git = requireNonNull(git, "git is null");
    }

    public boolean isAvailable()
    {
        return codeGraph.isAvailable();
    }

    public Fingerprint fingerprint(Path checkout)
            throws IOException, InterruptedException
    {
        Path root = normalize(checkout);
        ensureIgnored(root);
        String head = git.headSha(root);
        String status = git.statusPorcelainZ(root);
        MessageDigest digest = sha256();
        digest.update(head.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(status.getBytes(StandardCharsets.UTF_8));
        for (Path dirtyFile : dirtyFiles(root, status)) {
            if (Files.isRegularFile(dirtyFile)) {
                digest.update(root.relativize(dirtyFile).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                hashContent(digest, dirtyFile);
                digest.update((byte) 0);
            }
        }
        return new Fingerprint(hex(digest.digest()));
    }

    public CodeGraphResult ensureIndexed(Path checkout, Fingerprint target, boolean force)
    {
        Path root = normalize(checkout);
        if (!isAvailable()) {
            return CodeGraphResult.error("CodeGraph CLI is not available on PATH.");
        }
        try {
            ensureIgnored(root);
            if (!Files.isDirectory(root.resolve(".codegraph"))) {
                codeGraph.init(root);
                return CodeGraphResult.ok("initialized CodeGraph for " + root);
            }
            if (force) {
                codeGraph.rebuild(root);
                return CodeGraphResult.ok("rebuilt CodeGraph for " + root);
            }
            codeGraph.sync(root);
            return CodeGraphResult.ok("synced CodeGraph for " + root + " at " + target.value());
        }
        catch (IOException | RuntimeException e) {
            return CodeGraphResult.error(e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodeGraphResult.error("interrupted while indexing CodeGraph for " + root);
        }
    }

    public String explore(Path checkout, String query)
            throws IOException, InterruptedException
    {
        return codeGraph.explore(normalize(checkout), query);
    }

    private void ensureIgnored(Path checkout)
            throws IOException, InterruptedException
    {
        Path exclude = git.gitInfoExcludePath(checkout);
        Files.createDirectories(exclude.getParent());
        String current = Files.exists(exclude)
                ? Files.readString(exclude, StandardCharsets.UTF_8)
                : "";
        if (current.lines().anyMatch(CODEGRAPH_EXCLUDE::equals)) {
            return;
        }
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        Files.writeString(exclude, prefix + CODEGRAPH_EXCLUDE + "\n",
                StandardCharsets.UTF_8,
                Files.exists(exclude)
                        ? StandardOpenOption.APPEND
                        : StandardOpenOption.CREATE);
    }

    private static Set<Path> dirtyFiles(Path root, String status)
    {
        Set<Path> out = new LinkedHashSet<>();
        String[] entries = status.split(String.valueOf((char) 0), -1);
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.length() < 4) {
                continue;
            }
            String code = entry.substring(0, 2);
            String path = entry.substring(3);
            if (!path.isBlank()) {
                out.add(root.resolve(path).normalize());
            }
            if ((code.startsWith("R") || code.startsWith("C")) && i + 1 < entries.length) {
                String oldPath = entries[++i];
                if (!oldPath.isBlank()) {
                    out.add(root.resolve(oldPath).normalize());
                }
            }
        }
        return out;
    }

    private static void hashContent(MessageDigest digest, Path file)
            throws IOException
    {
        // ponytail: hash full contents only up to MAX_HASH_BYTES; larger dirty files
        // fall back to size+mtime so one huge untracked blob can't stall the per-turn
        // fingerprint. Raise the cap if content-level precision on big files matters.
        long size = Files.size(file);
        if (size > MAX_HASH_BYTES) {
            digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis())
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        hashFile(digest, file);
    }

    private static void hashFile(MessageDigest digest, Path file)
            throws IOException
    {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static Path normalize(Path path)
    {
        return path.toAbsolutePath().normalize();
    }

    public record Fingerprint(String value) {}
}
