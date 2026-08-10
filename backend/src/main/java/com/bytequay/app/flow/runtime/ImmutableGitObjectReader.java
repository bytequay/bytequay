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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Bounded read-only access to two complete immutable local Git objects. */
public final class ImmutableGitObjectReader
{
    private static final Path GIT = Path.of("/usr/bin/git");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BLOB_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TREE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIFF_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    private final Path repositoryRoot;
    private final String baseHead;
    private final String reviewedHead;

    public ImmutableGitObjectReader(
            Path programOwnedRepositoryRoot,
            String baseHead,
            String reviewedHead)
    {
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireObjectId(baseHead, "baseHead");
        requireObjectId(reviewedHead, "reviewedHead");
        try {
            this.repositoryRoot = programOwnedRepositoryRoot.toRealPath();
        }
        catch (IOException exception) {
            throw new IllegalArgumentException(
                    "repository root is unavailable", exception);
        }
        this.baseHead = baseHead;
        this.reviewedHead = reviewedHead;
        assertSafeCompleteRepository();
        requireCommit(baseHead);
        requireCommit(reviewedHead);
    }

    public record TreeEntry(
            String mode, String objectType, String objectId, String path)
    {
        public TreeEntry
        {
            requireText(mode, "mode");
            requireText(objectType, "objectType");
            requireObjectId(objectId, "objectId");
            requireText(path, "path");
        }
    }

    /** Lists the reviewed tree without consulting the worktree or filters. */
    public List<TreeEntry> listTree()
    {
        byte[] output = run(
                MAX_TREE_BYTES,
                "ls-tree",
                "-r",
                "-z",
                "--full-tree",
                reviewedHead);
        List<TreeEntry> entries = new ArrayList<>();
        int start = 0;
        while (start < output.length) {
            int end = indexOf(output, (byte) 0, start);
            if (end < 0) {
                throw new IllegalStateException(
                        "Git tree output is not NUL terminated");
            }
            String entry = decode(output, start, end - start);
            int firstSpace = entry.indexOf(' ');
            int secondSpace = entry.indexOf(' ', firstSpace + 1);
            int tab = entry.indexOf('\t', secondSpace + 1);
            if (firstSpace <= 0 || secondSpace <= firstSpace
                    || tab <= secondSpace) {
                throw new IllegalStateException("Git tree output is malformed");
            }
            String mode = entry.substring(0, firstSpace);
            String type = entry.substring(firstSpace + 1, secondSpace);
            String objectId = entry.substring(secondSpace + 1, tab);
            String path = entry.substring(tab + 1);
            requireSafeRelativePath(path);
            if (type.equals("commit") || mode.equals("160000")) {
                throw new IllegalStateException(
                        "reviewed tree contains an unsupported gitlink");
            }
            entries.add(new TreeEntry(mode, type, objectId, path));
            if (entries.size() > 20_000) {
                throw new IllegalStateException("reviewed tree is too large");
            }
            start = end + 1;
        }
        return List.copyOf(entries);
    }

    /** Reads one raw blob from the reviewed commit only. */
    public byte[] readReviewedBlob(String path)
    {
        requireSafeRelativePath(path);
        return run(MAX_BLOB_BYTES,
                "cat-file", "blob", reviewedHead + ":" + path);
    }

    public byte[] readBaseBlob(String path)
    {
        requireSafeRelativePath(path);
        return run(MAX_BLOB_BYTES,
                "cat-file", "blob", baseHead + ":" + path);
    }

    /** Reads a raw NUL-delimited object-change manifest, never a patch. */
    public byte[] readDiff()
    {
        return run(
                MAX_DIFF_BYTES,
                "diff-tree",
                "--raw",
                "-z",
                "--no-commit-id",
                "--no-ext-diff",
                "--no-textconv",
                "--full-index",
                "--no-renames",
                "-r",
                baseHead,
                reviewedHead,
                "--");
    }

    private void assertSafeCompleteRepository()
    {
        requireSafeConfig(run(MAX_CONFIG_BYTES, "config", "--null", "--list"));
        String commonValue = decode(run(
                16 * 1024, "rev-parse", "--git-common-dir"));
        Path common = repositoryRoot.resolve(commonValue.strip()).normalize();
        try {
            common = common.toRealPath();
            Path objects = common.resolve("objects");
            requireDirectoryNoFollow(objects);
            Path pack = objects.resolve("pack");
            if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryNoFollow(pack);
                try (var paths = Files.list(pack)) {
                    if (paths.anyMatch(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".promisor"))) {
                        throw new IllegalStateException(
                                "repository contains promisor objects");
                    }
                }
            }
            if (Files.exists(
                    objects.resolve("info/alternates"),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "repository uses an alternate object store");
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException(
                    "repository object store is unavailable", exception);
        }
    }

    private void requireCommit(String objectId)
    {
        run(1024, "cat-file", "-e", objectId + "^{commit}");
    }

    private byte[] run(int maxBytes, String... arguments)
    {
        CommandResult result = execute(maxBytes, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "bounded immutable Git read failed");
        }
        return result.output();
    }

    private static void requireSafeConfig(byte[] output)
    {
        int start = 0;
        while (start < output.length) {
            int end = indexOf(output, (byte) 0, start);
            if (end < 0) {
                throw new IllegalStateException(
                        "Git config output is not NUL terminated");
            }
            String entry = decode(output, start, end - start);
            int separator = entry.indexOf('\n');
            if (separator <= 0) {
                throw new IllegalStateException("Git config output is malformed");
            }
            String key = entry.substring(0, separator)
                    .toLowerCase(Locale.ROOT);
            String value = entry.substring(separator + 1)
                    .strip().toLowerCase(Locale.ROOT);
            boolean unsafe = key.equals("extensions.partialclone")
                    || key.equals("core.alternaterefscommand")
                    || key.startsWith("remote.")
                        && (key.endsWith(".partialclonefilter")
                            || key.endsWith(".promisor")
                                && gitBooleanIsNotFalse(value));
            if (unsafe) {
                throw new IllegalStateException(
                        "repository config permits external or partial objects");
            }
            start = end + 1;
        }
    }

    private static boolean gitBooleanIsNotFalse(String value)
    {
        return switch (value) {
            case "false", "no", "off", "0" -> false;
            case "", "true", "yes", "on", "1" -> true;
            default -> throw new IllegalStateException(
                    "repository has an invalid promisor boolean");
        };
    }

    private CommandResult execute(int maxBytes, String... arguments)
    {
        List<String> command = new ArrayList<>();
        command.add(GIT.toString());
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        var environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("LC_ALL", "C");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_NO_LAZY_FETCH", "1");
        environment.put("GIT_NO_REPLACE_OBJECTS", "1");
        environment.put("GIT_ATTR_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException exception) {
            throw new IllegalStateException("trusted Git is unavailable",
                    exception);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            AtomicReference<RuntimeException> readFailure =
                    new AtomicReference<>();
            Thread outputReader = Thread.startVirtualThread(() -> {
                try {
                    copyBounded(process.getInputStream(), output, maxBytes);
                }
                catch (RuntimeException failure) {
                    readFailure.compareAndSet(null, failure);
                }
            });
            Thread errorReader = Thread.startVirtualThread(() -> {
                try {
                    copyBounded(
                            process.getErrorStream(), error, MAX_STDERR_BYTES);
                }
                catch (RuntimeException failure) {
                    readFailure.compareAndSet(null, failure);
                }
            });
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (readFailure.get() != null
                        || System.nanoTime() >= deadline) {
                    terminate(process);
                    RuntimeException failure = readFailure.get();
                    if (failure != null) {
                        throw failure;
                    }
                    throw new IllegalStateException(
                            "immutable Git read timed out");
                }
            }
            outputReader.join(TIMEOUT);
            errorReader.join(TIMEOUT);
            if (outputReader.isAlive() || errorReader.isAlive()) {
                terminate(process);
                throw new IllegalStateException(
                        "immutable Git output did not close");
            }
            RuntimeException failure = readFailure.get();
            if (failure != null) {
                throw failure;
            }
            return new CommandResult(
                    process.exitValue(),
                    output.toByteArray());
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException(
                    "immutable Git read was interrupted", exception);
        }
        finally {
            terminate(process);
        }
    }

    private static void terminate(Process process)
    {
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void copyBounded(
            InputStream input, ByteArrayOutputStream output, int maxBytes)
    {
        byte[] buffer = new byte[8192];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maxBytes) {
                    throw new IllegalStateException(
                            "immutable Git output exceeded its bound");
                }
                output.write(buffer, 0, count);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException(
                    "immutable Git output failed", exception);
        }
    }

    private static void requireDirectoryNoFollow(Path path)
            throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IllegalStateException(
                    "repository object path is not a local directory");
        }
    }

    private static int indexOf(byte[] bytes, byte target, int start)
    {
        for (int index = start; index < bytes.length; index++) {
            if (bytes[index] == target) {
                return index;
            }
        }
        return -1;
    }

    private static String decode(byte[] bytes)
    {
        return decode(bytes, 0, bytes.length);
    }

    private static String decode(byte[] bytes, int offset, int length)
    {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        }
        catch (CharacterCodingException exception) {
            throw new IllegalStateException(
                    "Git emitted malformed UTF-8 metadata", exception);
        }
    }

    private static void requireSafeRelativePath(String value)
    {
        requireText(value, "path");
        Path path = Path.of(value);
        if (path.isAbsolute()
                || !path.normalize().equals(path)
                || value.equals("..")
                || value.startsWith("../")
                || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("path is not a safe relative path");
        }
    }

    private static void requireObjectId(String value, String name)
    {
        requireText(value, name);
        if ((value.length() != 40 && value.length() != 64)
                || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    name + " must be a full lowercase object ID");
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private record CommandResult(int exitCode, byte[] output) {}
}
