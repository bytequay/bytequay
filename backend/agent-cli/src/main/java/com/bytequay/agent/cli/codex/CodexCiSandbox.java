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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/** The local Git writes a linked CI worktree needs; other branch refs and shared configuration stay protected. */
final class CodexCiSandbox
{
    private static final int MAXIMUM_METADATA_BYTES = 4096;

    private CodexCiSandbox() {}

    static List<String> writableRoots(Path worktree)
    {
        requireNonNull(worktree, "worktree is null");
        try {
            Path cwd = worktree.toRealPath();
            checkState(Files.isDirectory(cwd), "Codex CI worktree is not a directory");
            Path gitFile = cwd.resolve(".git");
            String pointer = metadata(gitFile);
            checkState(pointer.startsWith("gitdir: "), "Codex CI requires a linked worktree .git file");
            Path gitDirectory = canonicalDirectory(cwd.resolve(pointer.substring("gitdir: ".length())));
            Path commonDirectory = canonicalDirectory(gitDirectory.resolve(metadata(gitDirectory.resolve("commondir"))));
            checkState(gitDirectory.getParent().equals(commonDirectory.resolve("worktrees")), "Codex CI Git directory is outside the common worktrees directory");
            Path backPointer = gitDirectory.resolve(metadata(gitDirectory.resolve("gitdir")));
            requireUnlinkedPath(backPointer);
            checkState(backPointer.toRealPath().equals(gitFile), "Codex CI Git back-pointer does not match the worktree");
            String head = metadata(gitDirectory.resolve("HEAD"));
            Path objects = canonicalDirectory(commonDirectory.resolve("objects"));
            if (!head.startsWith("ref: refs/heads/")) {
                requireRebase(gitDirectory, head);
                return ImmutableList.of(cwd.toString(), gitDirectory.toString(), objects.toString());
            }
            String ref = head.substring("ref: ".length());
            checkState(validRef(ref), "Codex CI branch ref is unsafe");
            Path branch = commonDirectory.resolve(ref);
            Path reflog = commonDirectory.resolve("logs").resolve(ref);
            ImmutableList<Path> roots = ImmutableList.of(cwd, gitDirectory, objects, branch, Path.of(branch + ".lock"), reflog, Path.of(reflog + ".lock"));
            for (Path root : roots) {
                requireUnlinkedPath(root);
            }
            for (Path root : roots.subList(3, roots.size())) {
                checkState(!Files.exists(root, NOFOLLOW_LINKS) || Files.isRegularFile(root, NOFOLLOW_LINKS), "Codex CI branch metadata is not a regular file: %s", root);
            }
            return roots.stream().map(Path::toString).collect(ImmutableList.toImmutableList());
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Codex CI Git metadata cannot be verified", failure);
        }
    }

    private static void requireRebase(Path gitDirectory, String head)
            throws IOException
    {
        checkState(validObjectId(head), "Codex CI requires a named local branch or an in-progress rebase");
        Path merge = gitDirectory.resolve("rebase-merge");
        Path apply = gitDirectory.resolve("rebase-apply");
        checkState(Files.exists(merge, NOFOLLOW_LINKS) != Files.exists(apply, NOFOLLOW_LINKS), "Codex CI requires exactly one rebase state directory");
        Path rebase = canonicalDirectory(Files.exists(merge, NOFOLLOW_LINKS) ? merge : apply);
        String ref = metadata(rebase.resolve("head-name"));
        checkState(ref.startsWith("refs/heads/") && validRef(ref), "Codex CI rebase branch ref is unsafe");
        checkState(validObjectId(metadata(rebase.resolve("orig-head"))) && validObjectId(metadata(rebase.resolve("onto"))), "Codex CI rebase commit metadata is unsafe");
    }

    private static boolean validObjectId(String value)
    {
        return value.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    }

    private static String metadata(Path path)
            throws IOException
    {
        checkState(Files.isRegularFile(path, NOFOLLOW_LINKS), "Codex CI metadata is not a regular file: %s", path);
        requireUnlinkedPath(path);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAXIMUM_METADATA_BYTES + 1);
        }
        checkState(bytes.length <= MAXIMUM_METADATA_BYTES, "Codex CI metadata is too large: %s", path);
        String value = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
        checkState(!value.isEmpty() && !value.contains("\n") && !value.contains("\r"), "Codex CI metadata must contain one nonempty line: %s", path);
        return value;
    }

    private static Path canonicalDirectory(Path path)
            throws IOException
    {
        requireUnlinkedPath(path.toAbsolutePath());
        Path normalized = path.toAbsolutePath().normalize();
        requireUnlinkedPath(normalized);
        checkState(Files.isDirectory(normalized, NOFOLLOW_LINKS), "Codex CI Git path is not a directory: %s", normalized);
        return normalized.toRealPath();
    }

    private static void requireUnlinkedPath(Path path)
    {
        for (Path current = path; current != null; current = current.getParent()) {
            checkState(!Files.isSymbolicLink(current), "Codex CI sandbox path contains a symbolic link: %s", current);
        }
    }

    private static boolean validRef(String ref)
    {
        if (ref.endsWith("/") || ref.endsWith(".") || ref.contains("..") || ref.contains("//") || ref.contains("@{")) {
            return false;
        }
        for (String component : ref.split("/")) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return ref.chars().noneMatch(value -> value <= ' ' || value == 127 || "~^:?*[\\".indexOf(value) >= 0);
    }
}
