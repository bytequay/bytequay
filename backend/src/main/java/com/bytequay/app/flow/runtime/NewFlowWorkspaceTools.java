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
package com.bytequay.app.flow.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Bounded worktree operations; no argv or repository identity is exposed. */
final class NewFlowWorkspaceTools
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 20_000;
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final int MAX_SEARCH_FILES = 2_000;
    private static final long MAX_SEARCH_BYTES = 64L * 1024 * 1024;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final int STDOUT_LIMIT = 1024 * 1024;
    private static final int STDERR_LIMIT = 64 * 1024;

    private final Path root;

    NewFlowWorkspaceTools(Path programOwnedWorktree)
    {
        requireNonNull(programOwnedWorktree, "programOwnedWorktree is null");
        try {
            this.root = programOwnedWorktree.toRealPath();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "program-owned worktree is unavailable", failure);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException(
                    "program-owned worktree is not a real directory");
        }
    }

    List<String> listRepository()
    {
        List<String> files = new ArrayList<>();
        int[] entries = {0};
        try {
            Files.walkFileTree(root, Set.of(), 32,
                    new SimpleFileVisitor<>()
                    {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes)
                        {
                            count(entries);
                            if (!directory.equals(root)
                                    && exactGitPath(directory)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (Files.isSymbolicLink(directory)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(
                                Path file, BasicFileAttributes attributes)
                        {
                            count(entries);
                            if (attributes.isRegularFile()
                                    && !exactGitPath(file)
                                    && files.size() < MAX_FILES) {
                                files.add(relative(file));
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            files.sort(String::compareTo);
            return List.copyOf(files);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot list worktree", failure);
        }
    }

    String readFile(String relativePath)
    {
        Path file = existingFile(relativePath);
        try {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(
                    file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds read bound");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot read worktree file", failure);
        }
    }

    List<String> search(String query)
    {
        requireText(query, "query");
        if (query.length() > 512) {
            throw new IllegalArgumentException("search query is too long");
        }
        List<String> results = new ArrayList<>();
        long[] bytes = {0};
        long deadline = System.nanoTime() + SEARCH_TIMEOUT.toNanos();
        int files = 0;
        for (String path : listRepository()) {
            if (files++ >= MAX_SEARCH_FILES
                    || bytes[0] >= MAX_SEARCH_BYTES
                    || System.nanoTime() >= deadline
                    || results.size() >= MAX_SEARCH_RESULTS) {
                break;
            }
            results.addAll(matchingLines(
                    path, query, bytes, deadline,
                    MAX_SEARCH_RESULTS - results.size()));
        }
        return List.copyOf(results);
    }

    void writeFile(String relativePath, String content)
    {
        requireNonNull(content, "content is null");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file exceeds write bound");
        }
        Path file = writableFile(relativePath);
        try {
            Files.createDirectories(file.getParent());
            assertRealParent(file);
            try (var channel = Files.newByteChannel(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot write worktree file", failure);
        }
    }

    /**
     * Replaces an inclusive one-based line range without exposing a shell.
     * This is the convenient path for large conflict regions: it has the same
     * real-path and size bounds as {@link #writeFile}, while avoiding a native
     * {@code sed} or {@code perl} permission prompt.
     */
    void replaceFileLines(
            String relativePath,
            int startLine,
            int endLine,
            String replacement)
    {
        requireNonNull(replacement, "replacement is null");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid line range");
        }
        String original = readFile(relativePath);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < original.length(); index++) {
            if (original.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        int lineCount = original.isEmpty()
                ? 0
                : starts.size() - (original.endsWith("\n") ? 1 : 0);
        if (endLine > lineCount) {
            throw new IllegalArgumentException("line range exceeds file");
        }
        int startOffset = starts.get(startLine - 1);
        int endOffset = endLine < starts.size()
                ? starts.get(endLine) : original.length();
        String inserted = replacement;
        if (!inserted.isEmpty() && endOffset < original.length()
                && !inserted.endsWith("\n")) {
            inserted += original.contains("\r\n") ? "\r\n" : "\n";
        }
        writeFile(
                relativePath,
                original.substring(0, startOffset)
                        + inserted
                        + original.substring(endOffset));
    }

    void deleteFile(String relativePath)
    {
        Path file = existingFile(relativePath);
        try {
            Files.delete(file);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot delete worktree file", failure);
        }
    }

    /** Fixed local-only commit; the model supplies neither message nor argv. */
    String commitRepair()
    {
        return commit("Apply CI repair");
    }

    /** Message was resolved from the CI owner's exact eligible target. */
    String commitRepair(String programOwnedMessage)
    {
        requireNonNull(programOwnedMessage, "programOwnedMessage is null");
        if (!programOwnedMessage.equals("Apply CI repair")
                && !programOwnedMessage.startsWith("fixup! ")) {
            throw new IllegalArgumentException(
                    "CI repair commit message is not program-owned");
        }
        return commit(programOwnedMessage);
    }

    String commitTaskChange()
    {
        return commit("Implement Task change");
    }

    private String commit(String message)
    {
        rejectGitFilters();
        run(false, "add", "-A");
        if (run(true, "diff", "--cached", "--quiet") == 0) {
            return head();
        }
        run(false, "commit", "-m", message);
        return head();
    }

    String head()
    {
        CommandResult result = execute(null, "rev-parse", "--verify", "HEAD");
        String head = new String(
                result.stdout(), StandardCharsets.US_ASCII).trim();
        if (result.exitCode() != 0 || !head.matches("[0-9a-f]{40,64}")) {
            throw new IllegalStateException("Git head read failed");
        }
        return head;
    }

    private List<String> matchingLines(
            String path, String query, long[] aggregateBytes,
            long deadline, int remainingResults)
    {
        try {
            Path file = existingFile(path);
            long remaining = Math.min(
                    MAX_FILE_BYTES, MAX_SEARCH_BYTES - aggregateBytes[0]);
            if (remaining <= 0 || Files.size(file) > remaining) {
                return List.of();
            }
            List<String> matches = new ArrayList<>();
            byte[] content;
            try (InputStream input = Files.newInputStream(
                    file, LinkOption.NOFOLLOW_LINKS)) {
                content = input.readNBytes((int) remaining + 1);
            }
            if (content.length > remaining) {
                return List.of();
            }
            aggregateBytes[0] += content.length;
            String[] lines = new String(
                    content, StandardCharsets.UTF_8).split("\\R", -1);
            for (int index = 0; index < lines.length
                    && System.nanoTime() < deadline; index++) {
                if (lines[index].contains(query)) {
                    matches.add(path + ":" + (index + 1) + ":" + lines[index]);
                    if (matches.size() >= remainingResults) {
                        break;
                    }
                }
            }
            return matches;
        }
        catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private int run(boolean allowOne, String... arguments)
    {
        int exit = execute(null, arguments).exitCode();
        if (exit != 0 && !(allowOne && exit == 1)) {
            throw new IllegalStateException("fixed Git command failed");
        }
        return exit;
    }

    private void rejectGitFilters()
    {
        CommandResult trackedResult = execute(
                null, "diff", "--name-only", "-z");
        CommandResult stagedResult = execute(
                null, "diff", "--cached", "--name-only", "-z");
        CommandResult untrackedResult = execute(
                null, "ls-files", "--others", "--exclude-standard", "-z");
        requireSuccess(trackedResult, "changed-path inspection failed");
        requireSuccess(stagedResult, "staged-path inspection failed");
        requireSuccess(untrackedResult, "untracked-path inspection failed");
        byte[] tracked = trackedResult.stdout();
        byte[] staged = stagedResult.stdout();
        byte[] untracked = untrackedResult.stdout();
        byte[] paths = new byte[
                tracked.length + staged.length + untracked.length];
        System.arraycopy(tracked, 0, paths, 0, tracked.length);
        System.arraycopy(staged, 0, paths, tracked.length, staged.length);
        System.arraycopy(untracked, 0, paths, tracked.length + staged.length,
                untracked.length);
        if (nulCount(paths) > 2_000) {
            throw new IllegalStateException("too many changed paths to stage");
        }
        if (paths.length == 0) {
            return;
        }
        CommandResult attributes = execute(
                paths, "check-attr", "-z", "--stdin", "filter");
        requireSuccess(attributes, "Git filter inspection failed");
        List<String> fields = nulFields(attributes.stdout());
        if (fields.size() % 3 != 0) {
            throw new IllegalStateException("Git filter inspection was malformed");
        }
        for (int index = 2; index < fields.size(); index += 3) {
            String value = fields.get(index);
            if (!value.equals("unspecified") && !value.equals("unset")) {
                throw new IllegalStateException(
                        "changed path uses a Git clean filter");
            }
        }
    }

    private CommandResult execute(byte[] stdin, String... arguments)
    {
        List<String> command = new ArrayList<>();
        command.add(GIT.toString());
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-c");
        command.add("commit.gpgSign=false");
        command.add("-c");
        command.add("user.name=ByteQuay");
        command.add("-c");
        command.add("user.email=bytequay@localhost");
        command.add("-c");
        command.add("core.fsmonitor=false");
        command.add("-c");
        command.add("gc.auto=0");
        command.add("-c");
        command.add("maintenance.auto=false");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_NO_REPLACE_OBJECTS", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot start fixed Git command", failure);
        }
        AtomicReference<Drain> stdout = new AtomicReference<>();
        AtomicReference<Drain> stderr = new AtomicReference<>();
        Thread out = Thread.ofVirtual().start(() -> stdout.set(
                drain(process.getInputStream(), STDOUT_LIMIT)));
        Thread err = Thread.ofVirtual().start(() -> stderr.set(
                drain(process.getErrorStream(), STDERR_LIMIT)));
        try {
            if (stdin != null) {
                process.getOutputStream().write(stdin);
            }
            process.getOutputStream().close();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                terminate(process, out, err);
                throw new IllegalStateException("fixed Git command timed out");
            }
            out.join(5_000);
            err.join(5_000);
            if (out.isAlive() || err.isAlive()) {
                process.getInputStream().close();
                process.getErrorStream().close();
                out.join(5_000);
                err.join(5_000);
                if (out.isAlive() || err.isAlive()) {
                    throw new IllegalStateException(
                            "fixed Git process boundary is unproven");
                }
            }
            Drain output = requireNonNull(stdout.get(), "stdout drain missing");
            Drain errors = requireNonNull(stderr.get(), "stderr drain missing");
            if (!output.eof() || !errors.eof()
                    || output.exceeded() || errors.exceeded()) {
                throw new IllegalStateException(
                        "fixed Git command output was not bounded and complete");
            }
            return new CommandResult(
                    process.exitValue(), output.bytes(), errors.bytes());
        }
        catch (InterruptedException interrupted) {
            terminate(process, out, err);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "fixed Git command interrupted", interrupted);
        }
        catch (IOException failure) {
            terminate(process, out, err);
            throw new UncheckedIOException(
                    "fixed Git command input failed", failure);
        }
    }

    private static Drain drain(InputStream stream, int limit)
    {
        try (stream) {
            byte[] kept = stream.readNBytes(limit + 1);
            boolean exceeded = kept.length > limit;
            stream.transferTo(OutputStream.nullOutputStream());
            byte[] bytes = exceeded
                    ? Arrays.copyOf(kept, limit) : kept;
            return new Drain(bytes, exceeded, true);
        }
        catch (IOException failure) {
            return new Drain(new byte[0], false, false);
        }
    }

    private static void terminate(Process process, Thread out, Thread err)
    {
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Git process death is unproven");
            }
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            out.join(5_000);
            err.join(5_000);
            if (out.isAlive() || err.isAlive()) {
                throw new IllegalStateException("Git pipe closure is unproven");
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("cannot close Git process", failure);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git termination interrupted", interrupted);
        }
    }

    private Path existingFile(String relativePath)
    {
        Path file = writableFile(relativePath);
        try {
            Path real = file.toRealPath();
            if (!real.startsWith(root)
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(real)) {
                throw new IllegalArgumentException("path is not a safe file");
            }
            return real;
        }
        catch (IOException failure) {
            throw new IllegalArgumentException("file is unavailable", failure);
        }
    }

    private Path writableFile(String relativePath)
    {
        requireText(relativePath, "path");
        if (relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path contains NUL");
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || gitMetadataPath(relative)) {
            throw new IllegalArgumentException("path is outside the worktree");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("path escaped the worktree");
        }
        Path cursor = root;
        for (Path component : root.relativize(resolved)) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("path crosses a symlink");
            }
        }
        return resolved;
    }

    private void assertRealParent(Path file)
            throws IOException
    {
        Path parent = file.getParent().toRealPath();
        if (!parent.startsWith(root) || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("file parent escaped worktree");
        }
    }

    private String relative(Path path)
    {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private boolean exactGitPath(Path path)
    {
        Path relative = root.relativize(path);
        return gitMetadataPath(relative);
    }

    private static boolean gitMetadataPath(Path relative)
    {
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase(".git");
    }

    private static void count(int[] entries)
    {
        if (++entries[0] > MAX_ENTRIES) {
            throw new IllegalStateException(
                    "worktree traversal exceeds entry bound");
        }
    }

    private static int nulCount(byte[] bytes)
    {
        int count = 0;
        for (byte value : bytes) {
            if (value == 0) {
                count++;
            }
        }
        return count;
    }

    private static void requireSuccess(CommandResult result, String message)
    {
        if (result.exitCode() != 0) {
            throw new IllegalStateException(message);
        }
    }

    private static List<String> nulFields(byte[] bytes)
    {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == 0) {
                fields.add(new String(
                        bytes, start, index - start, StandardCharsets.UTF_8));
                start = index + 1;
            }
        }
        if (start != bytes.length) {
            throw new IllegalStateException("NUL-delimited Git output is partial");
        }
        return fields;
    }

    private record Drain(byte[] bytes, boolean exceeded, boolean eof) {}

    private record CommandResult(
            int exitCode, byte[] stdout, byte[] stderr) {}

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
