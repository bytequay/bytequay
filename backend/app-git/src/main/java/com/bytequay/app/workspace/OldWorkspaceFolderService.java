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
package com.bytequay.app.workspace;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/**
 * Lists workspace folders that no workspace in the current database owns. A new database file starts empty and leaves the folders
 * of the previous one behind; an older release that still opens that file needs them, so only the user removes them.
 */
@Service
public class OldWorkspaceFolderService
{
    private static final Pattern REPOSITORY = Pattern.compile("(?:[a-z+]+://[^/]+/|[^/@]+@[^:/]+:)(.+?)(?:\\.git)?/?");

    private final WorkspaceStore workspaces;
    private final WorkspacePaths paths;
    private final GitCommandRunner git;

    public OldWorkspaceFolderService(WorkspaceStore workspaces, WorkspacePaths paths, GitCommandRunner git)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.git = requireNonNull(git, "git is null");
    }

    /** Old folders, largest first. */
    public List<OldWorkspaceFolder> list()
    {
        if (!Files.isDirectory(paths.root(), NOFOLLOW_LINKS)) {
            return ImmutableList.of();
        }
        try (Stream<Path> children = Files.list(paths.root())) {
            return children
                    .filter(this::old)
                    .map(this::describe)
                    .sorted(Comparator.comparingLong(OldWorkspaceFolder::sizeBytes).reversed())
                    .collect(toImmutableList());
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** One old folder's path, checked again right before the user moves it away. */
    public Path path(String id)
    {
        Path folder = paths.workspace(id);
        if (!old(folder)) {
            throw WorkspaceException.invalidRepository("This folder is not an old workspace folder.");
        }
        return folder;
    }

    private boolean old(Path folder)
    {
        return Files.isDirectory(folder, NOFOLLOW_LINKS) && owned(folder) && workspaces.findById(folder.getFileName().toString()).isEmpty();
    }

    /** A workspace's first line of ownership names its own folder, so nothing else under the root is ever listed. */
    private static boolean owned(Path folder)
    {
        Path marker = folder.resolve(ManagedWorkspaceGit.OWNERSHIP_FILE);
        try {
            return Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.readString(marker).startsWith(folder.getFileName() + "\n");
        }
        catch (IOException unreadable) {
            return false;
        }
    }

    private OldWorkspaceFolder describe(Path folder)
    {
        Path repository = folder.resolve("repository");
        List<Path> checkouts = children(folder.resolve("worktrees"));
        Instant modifiedAt = Stream.concat(Stream.of(folder, repository), checkouts.stream())
                .map(OldWorkspaceFolderService::modifiedAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return new OldWorkspaceFolder(folder.getFileName().toString(), folder.toString(), origin(repository), size(folder), modifiedAt, checkouts.size());
    }

    private Optional<String> origin(Path repository)
    {
        if (!Files.isDirectory(repository.resolve(".git"), NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        GitCommandRunner.Result result = git.run(repository, "config", "--get", "remote.origin.url");
        return result.successful() ? repositoryName(result.output().strip()) : Optional.empty();
    }

    static Optional<String> repositoryName(String url)
    {
        Matcher matcher = REPOSITORY.matcher(url);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static List<Path> children(Path directory)
    {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            return ImmutableList.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.collect(toImmutableList());
        }
        catch (IOException unreadable) {
            return ImmutableList.of();
        }
    }

    private static Instant modifiedAt(Path path)
    {
        try {
            return Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toInstant();
        }
        catch (IOException missing) {
            return Instant.EPOCH;
        }
    }

    private static long size(Path folder)
    {
        AtomicLong total = new AtomicLong();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    total.addAndGet(attributes.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure)
                {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException unreadable) {
            // What could be read is still counted; the user decides from an estimate.
        }
        return total.get();
    }
}
