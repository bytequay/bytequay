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

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

public final class FileAgentResourceService
        implements AgentResourceService
{
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";

    private final Path runtimeRoot;

    public FileAgentResourceService(Path runtimeRoot)
    {
        requireNonNull(runtimeRoot, "runtimeRoot is null");
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
    }

    @Override
    public TaskResources allocate(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        try {
            Path root = createDirectory(runtimeRoot);
            Path taskDirectory = checkedTaskDirectory(root, taskId);
            rejectSymbolicLinks(root, taskDirectory);
            createDirectory(taskDirectory);
            Path eventsFile = taskDirectory.resolve("events.jsonl");
            rejectSymbolicLinks(root, eventsFile);
            createFile(eventsFile);
            Path blobsDirectory = taskDirectory.resolve("blobs");
            rejectSymbolicLinks(root, blobsDirectory);
            createDirectory(blobsDirectory);
            Path providerDirectory = taskDirectory.resolve("provider").resolve("scratch");
            rejectSymbolicLinks(root, providerDirectory);
            createDirectory(providerDirectory);
            return new TaskResources(taskDirectory, eventsFile, blobsDirectory, providerDirectory);
        }
        catch (IOException | RuntimeException e) {
            if (e instanceof AgentStorageException storageException) {
                throw storageException;
            }
            throw new AgentStorageException("Cannot allocate resources for task " + taskId, e);
        }
    }

    @Override
    public TaskResources require(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        try {
            Path root = requireDirectory(runtimeRoot);
            Path taskDirectory = checkedTaskDirectory(root, taskId);
            rejectSymbolicLinks(root, taskDirectory);
            requireDirectory(taskDirectory);
            Path eventsFile = requireFile(taskDirectory.resolve("events.jsonl"));
            Path blobsDirectory = taskDirectory.resolve("blobs");
            rejectSymbolicLinks(root, blobsDirectory);
            requireDirectory(blobsDirectory);
            Path providerDirectory = taskDirectory.resolve("provider").resolve("scratch");
            rejectSymbolicLinks(root, providerDirectory);
            requireDirectory(providerDirectory);
            return new TaskResources(taskDirectory, eventsFile, blobsDirectory, providerDirectory);
        }
        catch (IOException | RuntimeException e) {
            if (e instanceof AgentStorageException storageException) {
                throw storageException;
            }
            throw new AgentStorageException("Cannot resolve resources for task " + taskId, e);
        }
    }

    @Override
    public void delete(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        try {
            if (!Files.exists(runtimeRoot, NOFOLLOW_LINKS)) {
                return;
            }
            Path root = requireDirectory(runtimeRoot);
            Path taskDirectory = checkedTaskDirectory(root, taskId);
            if (!Files.exists(taskDirectory, NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(taskDirectory)) {
                throw new AgentStorageException("Task resource directory is a symbolic link");
            }
            Files.walkFileTree(taskDirectory, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException
                {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                        throws IOException
                {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException | RuntimeException e) {
            if (e instanceof AgentStorageException storageException) {
                throw storageException;
            }
            throw new AgentStorageException("Cannot delete resources for task " + taskId, e);
        }
    }

    private static Path checkedTaskDirectory(Path root, TaskId taskId)
    {
        Path taskDirectory = root.resolve(taskId.toString()).normalize();
        if (!taskDirectory.getParent().equals(root)) {
            throw new AgentStorageException("Task resource path escapes the runtime root");
        }
        return taskDirectory;
    }

    private static void rejectSymbolicLinks(Path root, Path child)
    {
        Path normalized = child.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new AgentStorageException("Task resource path escapes the runtime root");
        }
        Path current = root;
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new AgentStorageException("Task resource path contains a symbolic link: " + current);
            }
        }
    }

    private static Path createDirectory(Path directory)
            throws IOException
    {
        if (Files.exists(directory, NOFOLLOW_LINKS) && Files.isSymbolicLink(directory)) {
            throw new AgentStorageException("Runtime directory is a symbolic link: " + directory);
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new AgentStorageException("Runtime path is not a directory: " + directory);
        }
        setPermissions(directory, DIRECTORY_PERMISSIONS);
        return directory;
    }

    private static void createFile(Path file)
            throws IOException
    {
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            requireFile(file);
        }
        else {
            Files.createFile(file);
        }
        setPermissions(file, FILE_PERMISSIONS);
    }

    private static Path requireDirectory(Path directory)
            throws IOException
    {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(directory.toString());
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new AgentStorageException("Required runtime directory is unsafe: " + directory);
        }
        return directory;
    }

    private static Path requireFile(Path file)
            throws IOException
    {
        if (!Files.exists(file, NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(file.toString());
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            throw new AgentStorageException("Required runtime file is unsafe: " + file);
        }
        return file;
    }

    static void setOwnerFilePermissions(Path path)
            throws IOException
    {
        setPermissions(path, FILE_PERMISSIONS);
    }

    private static void setPermissions(Path path, String permissions)
            throws IOException
    {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }
}
