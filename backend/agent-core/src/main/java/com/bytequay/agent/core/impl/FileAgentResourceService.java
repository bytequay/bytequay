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
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

public final class FileAgentResourceService
        implements AgentResourceService
{
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";
    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    private static final long DEFAULT_MAX_ATTACHMENT_PIXELS = 30_000_000L;
    private static final int MAX_ATTACHMENT_NAME_LENGTH = 200;
    private static final Pattern ATTACHMENT_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** The image formats the JDK can read the dimensions of without decoding the pixels. */
    private static final Map<String, String> IMAGE_MEDIA_TYPES = Map.of(
            "png", "image/png",
            "jpeg", "image/jpeg",
            "gif", "image/gif");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path runtimeRoot;
    private final long maxAttachmentBytes;
    private final long maxAttachmentPixels;

    public FileAgentResourceService(Path runtimeRoot)
    {
        this(runtimeRoot, DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_ATTACHMENT_PIXELS);
    }

    public FileAgentResourceService(Path runtimeRoot, long maxAttachmentBytes, long maxAttachmentPixels)
    {
        requireNonNull(runtimeRoot, "runtimeRoot is null");
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachmentPixels = maxAttachmentPixels;
    }

    @Override
    public StoredAgentAttachment storeAttachment(StoreAgentAttachmentCommand command)
    {
        ImageFacts image = validatedImage(command);
        byte[] bytes = command.bytes();
        String name = displayName(command.name());
        String id = UUID.randomUUID().toString();
        try {
            Path directory = createDirectory(attachmentsDirectory(require(command.taskId())));
            Path blob = directory.resolve(id);
            Files.write(blob, bytes);
            setPermissions(blob, FILE_PERMISSIONS);
            Path facts = directory.resolve(id + ".json");
            Files.writeString(facts, MAPPER.writeValueAsString(new AttachmentFacts(
                    name, image.mediaType(), bytes.length, image.width(), image.height())));
            setPermissions(facts, FILE_PERMISSIONS);
            return new StoredAgentAttachment(id, name, image.mediaType(), bytes.length, image.width(), image.height(), blob);
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot store attachment for task " + command.taskId(), e);
        }
    }

    @Override
    public void validateAttachment(StoreAgentAttachmentCommand command)
    {
        validatedImage(command);
    }

    private ImageFacts validatedImage(StoreAgentAttachmentCommand command)
    {
        requireNonNull(command, "command is null");
        byte[] bytes = command.bytes();
        if (bytes.length == 0) {
            throw new InvalidAgentRequestException("Attachment is empty");
        }
        if (bytes.length > maxAttachmentBytes) {
            throw new InvalidAgentRequestException("Attachment exceeds " + maxAttachmentBytes + " bytes");
        }
        ImageFacts image = readImageFacts(bytes);
        String claimed = command.mediaType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!claimed.equals(image.mediaType())) {
            throw new InvalidAgentRequestException(
                    "Attachment claims " + claimed + " but its bytes are " + image.mediaType());
        }
        if ((long) image.width() * image.height() > maxAttachmentPixels) {
            throw new InvalidAgentRequestException("Attachment exceeds " + maxAttachmentPixels + " pixels");
        }
        return image;
    }

    @Override
    public StoredAgentAttachment readAttachment(TaskId taskId, String attachmentId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(attachmentId, "attachmentId is null");
        if (!ATTACHMENT_ID.matcher(attachmentId).matches()) {
            // Anything else is not a reference this service issued, so it cannot name a file here.
            throw new AgentAttachmentNotFoundException(taskId, attachmentId);
        }
        Path directory = attachmentsDirectory(require(taskId));
        Path blob = directory.resolve(attachmentId);
        Path facts = directory.resolve(attachmentId + ".json");
        rejectSymbolicLinks(runtimeRoot, blob);
        rejectSymbolicLinks(runtimeRoot, facts);
        if (!Files.isRegularFile(blob, NOFOLLOW_LINKS) || !Files.isRegularFile(facts, NOFOLLOW_LINKS)) {
            throw new AgentAttachmentNotFoundException(taskId, attachmentId);
        }
        try {
            AttachmentFacts stored = MAPPER.readValue(Files.readString(facts), AttachmentFacts.class);
            return new StoredAgentAttachment(
                    attachmentId, stored.name(), stored.mediaType(), stored.byteSize(), stored.width(), stored.height(), blob);
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot read attachment " + attachmentId + " of task " + taskId, e);
        }
    }

    @Override
    public void deleteAttachment(TaskId taskId, String attachmentId)
    {
        StoredAgentAttachment attachment = readAttachment(taskId, attachmentId);
        try {
            Files.deleteIfExists(attachment.path());
            Files.deleteIfExists(attachment.path().resolveSibling(attachmentId + ".json"));
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot delete attachment " + attachmentId + " of task " + taskId, e);
        }
    }

    private Path attachmentsDirectory(TaskResources resources)
    {
        Path directory = resources.blobsDirectory().resolve("attachments");
        rejectSymbolicLinks(runtimeRoot, directory);
        return directory;
    }

    /** A file name for display: no directories, bounded, never empty. */
    private static String displayName(String claimed)
    {
        Path name = Path.of(claimed.replace('\\', '/')).getFileName();
        String text = name == null ? "" : name.toString().trim();
        if (text.isEmpty() || text.equals(".") || text.equals("..")) {
            text = "image";
        }
        return text.length() > MAX_ATTACHMENT_NAME_LENGTH ? text.substring(0, MAX_ATTACHMENT_NAME_LENGTH) : text;
    }

    /** What the bytes actually are, read from their header; a claim never decides this. */
    private static ImageFacts readImageFacts(byte[] bytes)
    {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new InvalidAgentRequestException("Attachment is not a PNG, JPEG or GIF image");
            }
            ImageReader reader = readers.next();
            try {
                String mediaType = IMAGE_MEDIA_TYPES.get(reader.getFormatName().toLowerCase(Locale.ROOT));
                if (mediaType == null) {
                    throw new InvalidAgentRequestException("Attachment is not a PNG, JPEG or GIF image");
                }
                reader.setInput(stream);
                return new ImageFacts(mediaType, reader.getWidth(0), reader.getHeight(0));
            }
            finally {
                reader.dispose();
            }
        }
        catch (IOException e) {
            throw new InvalidAgentRequestException("Attachment is not a readable image");
        }
    }

    private record ImageFacts(String mediaType, int width, int height) {}

    /** The facts kept beside a blob; the id is the file name, so it is not repeated inside. */
    private record AttachmentFacts(String name, String mediaType, long byteSize, int width, int height) {}

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
