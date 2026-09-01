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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.ProviderAgentEventAdapter;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.toIntExact;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

public final class FileTaskLogStore
        implements TaskLogStore
{
    private static final int DEFAULT_MAX_BLOB_BYTES = 10 * 1024 * 1024;

    private final AgentResourceService resourceService;
    private final int maxEventBytes;
    private final int maxBlobBytes;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<TaskId, Object> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, Long> lastSequences = new ConcurrentHashMap<>();

    public FileTaskLogStore(AgentResourceService resourceService)
    {
        this(resourceService, AgentEventEnvelope.MAX_SERIALIZED_BYTES, DEFAULT_MAX_BLOB_BYTES);
    }

    public FileTaskLogStore(AgentResourceService resourceService, int maxEventBytes, int maxBlobBytes)
    {
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        checkArgument(maxEventBytes > 0 && maxBlobBytes > 0, "Log size limits must be positive");
        this.maxEventBytes = maxEventBytes;
        this.maxBlobBytes = maxBlobBytes;
        this.objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void append(AgentEventEnvelope envelope)
    {
        requireNonNull(envelope, "envelope is null");
        byte[] json = serialize(envelope);
        if (json.length > maxEventBytes) {
            throw new AgentStorageException("Event exceeds the configured size limit");
        }
        ByteBuffer line = ByteBuffer.allocate(json.length + 1)
                .put(json)
                .put((byte) '\n')
                .flip();

        synchronized (lock(envelope.taskId())) {
            Long cachedSequence = lastSequences.get(envelope.taskId());
            long lastSequence = cachedSequence == null ? recoverTail(envelope.taskId()) : cachedSequence;
            lastSequences.putIfAbsent(envelope.taskId(), lastSequence);
            if (envelope.sequence() != lastSequence + 1) {
                throw new AgentStorageException(
                        "Expected event sequence " + (lastSequence + 1) + " but received " + envelope.sequence());
            }
            Path eventsFile = resourceService.require(envelope.taskId()).eventsFile();
            long originalSize = -1;
            try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                originalSize = channel.size();
                while (line.hasRemaining()) {
                    channel.write(line);
                }
                channel.force(true);
                lastSequences.put(envelope.taskId(), envelope.sequence());
            }
            catch (IOException e) {
                lastSequences.remove(envelope.taskId());
                rollbackAppend(eventsFile, originalSize, e);
                throw new AgentStorageException("Cannot append task event", e);
            }
        }
    }

    @Override
    public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        checkArgument(afterSequence >= 0 && limit > 0, "afterSequence must be non-negative and limit must be positive");
        synchronized (lock(taskId)) {
            return readCompleteRecords(taskId, afterSequence, limit);
        }
    }

    @Override
    public Optional<Long> findSequence(TaskId taskId, EventId eventId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(eventId, "eventId is null");
        synchronized (lock(taskId)) {
            Path eventsFile = resourceService.require(taskId).eventsFile();
            Optional<Long> sequence = Optional.empty();
            long expectedSequence = 1;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(eventsFile))) {
                byte[] line;
                while ((line = readCompleteLine(input)) != null) {
                    AgentEventEnvelope envelope = deserialize(line);
                    validateSequence(taskId, envelope, expectedSequence++);
                    if (envelope.eventId().equals(eventId)) {
                        if (sequence.isPresent()) {
                            throw new AgentStorageException("Task log event identity is duplicated");
                        }
                        sequence = Optional.of(envelope.sequence());
                    }
                }
                return sequence;
            }
            catch (IOException e) {
                throw new AgentStorageException("Cannot read task log", e);
            }
        }
    }

    @Override
    public long lastSequence(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (lock(taskId)) {
            long sequence = recoverTail(taskId);
            lastSequences.put(taskId, sequence);
            return sequence;
        }
    }

    @Override
    public BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(content, "content is null");
        checkNotBlank(mediaType, "mediaType");
        ByteBuffer bytes = content.asReadOnlyBuffer();
        int size = bytes.remaining();
        if (size > maxBlobBytes) {
            throw new AgentStorageException("Blob exceeds the configured size limit");
        }

        synchronized (lock(taskId)) {
            Path blobsDirectory = resourceService.require(taskId).blobsDirectory();
            String contentId = UUID.randomUUID().toString();
            Path blob = blobsDirectory.resolve(contentId).normalize();
            if (!blob.getParent().equals(blobsDirectory)) {
                throw new AgentStorageException("Blob path escapes the task resource directory");
            }
            try (FileChannel channel = FileChannel.open(blob, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
                FileAgentResourceService.setOwnerFilePermissions(blob);
                return new BlobRef("blobs/" + contentId, mediaType, size);
            }
            catch (IOException e) {
                try {
                    Files.deleteIfExists(blob);
                }
                catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw new AgentStorageException("Cannot write task blob", e);
            }
        }
    }

    @Override
    public ByteBuffer readBlob(TaskId taskId, BlobRef blobRef)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(blobRef, "blobRef is null");
        if (blobRef.size() > maxBlobBytes) {
            throw new AgentStorageException("Blob exceeds the configured size limit");
        }

        synchronized (lock(taskId)) {
            TaskResources resources = resourceService.require(taskId);
            Path blob = resources.runtimeDirectory().resolve(blobRef.relativePath()).normalize();
            if (!resources.blobsDirectory().equals(blob.getParent()) || !Files.isRegularFile(blob, NOFOLLOW_LINKS)) {
                throw new AgentStorageException("Task blob reference is missing or unsafe");
            }
            try (FileChannel channel = FileChannel.open(blob, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                long size = channel.size();
                if (size != blobRef.size() || size > maxBlobBytes) {
                    throw new AgentStorageException("Task blob size does not match its reference");
                }
                ByteBuffer content = ByteBuffer.allocate(toIntExact(size));
                while (content.hasRemaining()) {
                    if (channel.read(content) < 0) {
                        throw new AgentStorageException("Task blob size does not match its reference");
                    }
                }
                if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                    throw new AgentStorageException("Task blob size does not match its reference");
                }
                return content.flip().asReadOnlyBuffer();
            }
            catch (IOException e) {
                throw new AgentStorageException("Cannot read task blob", e);
            }
        }
    }

    private Object lock(TaskId taskId)
    {
        return taskLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    private long readLastSequence(TaskId taskId)
    {
        List<AgentEventEnvelope> envelopes = readCompleteRecords(taskId, 0, Integer.MAX_VALUE);
        return envelopes.isEmpty() ? 0 : envelopes.getLast().sequence();
    }

    private static void rollbackAppend(Path eventsFile, long originalSize, IOException failure)
    {
        if (originalSize < 0) {
            return;
        }
        try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.WRITE)) {
            channel.truncate(originalSize);
            channel.force(true);
        }
        catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private long recoverTail(TaskId taskId)
    {
        Path eventsFile = resourceService.require(taskId).eventsFile();
        try (FileChannel channel = FileChannel.open(
                eventsFile,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (size > 0) {
                ByteBuffer byteBuffer = ByteBuffer.allocate(1);
                channel.read(byteBuffer, size - 1);
                if (byteBuffer.flip().get() != '\n') {
                    long lastNewline = findLastNewline(channel, size - 2);
                    channel.truncate(lastNewline + 1);
                    channel.force(true);
                }
            }
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot recover task log tail", e);
        }
        return readLastSequence(taskId);
    }

    private static long findLastNewline(FileChannel channel, long position)
            throws IOException
    {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1);
        for (long current = position; current >= 0; current--) {
            byteBuffer.clear();
            channel.read(byteBuffer, current);
            if (byteBuffer.flip().get() == '\n') {
                return current;
            }
        }
        return -1;
    }

    private List<AgentEventEnvelope> readCompleteRecords(TaskId taskId, long afterSequence, int limit)
    {
        Path eventsFile = resourceService.require(taskId).eventsFile();
        ImmutableList.Builder<AgentEventEnvelope> result = ImmutableList.builderWithExpectedSize(Math.min(limit, 100));
        long expectedSequence = 1;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(eventsFile))) {
            byte[] line;
            while ((line = readCompleteLine(input)) != null) {
                AgentEventEnvelope envelope = deserialize(line);
                validateSequence(taskId, envelope, expectedSequence);
                expectedSequence++;
                if (envelope.sequence() > afterSequence) {
                    result.add(envelope);
                    if (--limit == 0) {
                        break;
                    }
                }
            }
            return result.build();
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot read task log", e);
        }
    }

    private static void validateSequence(TaskId taskId, AgentEventEnvelope envelope, long expectedSequence)
    {
        if (!envelope.taskId().equals(taskId) || envelope.sequence() != expectedSequence) {
            throw new AgentStorageException("Task log sequence is corrupt");
        }
    }

    private byte[] readCompleteLine(InputStream input)
            throws IOException
    {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                return line.toByteArray();
            }
            if (line.size() == maxEventBytes) {
                throw new AgentStorageException("Task log record exceeds the configured size limit");
            }
            line.write(next);
        }
        return null;
    }

    private byte[] serialize(AgentEventEnvelope envelope)
    {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", envelope.schemaVersion());
        root.put("eventId", envelope.eventId().toString());
        root.put("sequence", envelope.sequence());
        root.put("taskId", envelope.taskId().toString());
        envelope.turnId().ifPresentOrElse(
                turnId -> root.put("turnId", turnId.toString()),
                () -> root.putNull("turnId"));
        root.set("source", objectMapper.valueToTree(envelope.source()));
        root.put("occurredAt", envelope.occurredAt().toString());
        root.put("type", envelope.type().name().toLowerCase(Locale.ROOT));
        root.set("payload", objectMapper.valueToTree(envelope.payload()));
        try {
            return objectMapper.writeValueAsBytes(root);
        }
        catch (JsonProcessingException e) {
            throw new AgentStorageException("Cannot serialize task event", e);
        }
    }

    private AgentEventEnvelope deserialize(byte[] line)
    {
        try {
            JsonNode root = objectMapper.readTree(line);
            int schemaVersion = root.required("schemaVersion").intValue();
            return switch (schemaVersion) {
                case 1 -> deserializeV1(root);
                case AgentEventEnvelope.CURRENT_SCHEMA_VERSION -> deserializeV2(root);
                default -> throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
            };
        }
        catch (IOException | RuntimeException e) {
            throw new AgentStorageException("Task log contains a corrupt record", e);
        }
    }

    private AgentEventEnvelope deserializeV1(JsonNode root)
            throws JsonProcessingException
    {
        ProviderAgentEvent.Kind kind = ProviderAgentEvent.Kind.valueOf(
                root.required("type").textValue().toUpperCase(Locale.ROOT));
        JsonNode eventNode = root.required("event");
        ProviderAgentEvent event = switch (kind) {
            case MESSAGE -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Message.class);
            case PLAN -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Plan.class);
            case TOOL -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Tool.class);
            case COMMAND -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Command.class);
            case FILE -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.FileChange.class);
            case APPROVAL -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Approval.class);
            case USAGE -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Usage.class);
            case LIFECYCLE -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Lifecycle.class);
            case ERROR -> objectMapper.treeToValue(eventNode, ProviderAgentEvent.Error.class);
        };
        long sequence = root.required("sequence").longValue();
        TaskId taskId = TaskId.valueOf(root.required("taskId").textValue());
        ProviderAgentEventAdapter.CanonicalEvent canonical = ProviderAgentEventAdapter.adaptLegacy(event);
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.legacy(taskId, sequence),
                sequence,
                taskId,
                turnId(root),
                canonical.source(),
                Instant.parse(root.required("occurredAt").textValue()),
                canonical.payload());
    }

    private AgentEventEnvelope deserializeV2(JsonNode root)
            throws JsonProcessingException
    {
        AgentEventPayload.Kind kind = AgentEventPayload.Kind.valueOf(
                root.required("type").textValue().toUpperCase(Locale.ROOT));
        JsonNode payloadNode = root.required("payload");
        AgentEventPayload payload = switch (kind) {
            case USER_MESSAGE -> objectMapper.treeToValue(payloadNode, AgentEventPayload.UserMessage.class);
            case ASSISTANT_MESSAGE -> objectMapper.treeToValue(payloadNode, AgentEventPayload.AssistantMessage.class);
            case PLAN_UPDATED -> objectMapper.treeToValue(payloadNode, AgentEventPayload.PlanUpdated.class);
            case COMMAND -> objectMapper.treeToValue(payloadNode, AgentEventPayload.CommandActivity.class);
            case FILE_CHANGE -> objectMapper.treeToValue(payloadNode, AgentEventPayload.FileActivity.class);
            case TOOL -> objectMapper.treeToValue(payloadNode, AgentEventPayload.ToolActivity.class);
            case APPROVAL_REQUESTED -> objectMapper.treeToValue(payloadNode, AgentEventPayload.ApprovalRequested.class);
            case APPROVAL_RESOLVED -> objectMapper.treeToValue(payloadNode, AgentEventPayload.ApprovalResolved.class);
            case USAGE -> objectMapper.treeToValue(payloadNode, AgentEventPayload.UsageUpdated.class);
            case LIFECYCLE -> objectMapper.treeToValue(payloadNode, AgentEventPayload.Lifecycle.class);
            case ERROR -> objectMapper.treeToValue(payloadNode, AgentEventPayload.Error.class);
        };
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.valueOf(root.required("eventId").textValue()),
                root.required("sequence").longValue(),
                TaskId.valueOf(root.required("taskId").textValue()),
                turnId(root),
                objectMapper.treeToValue(root.required("source"), AgentEventSource.class),
                Instant.parse(root.required("occurredAt").textValue()),
                payload);
    }

    private static Optional<TurnId> turnId(JsonNode root)
    {
        JsonNode turnId = root.required("turnId");
        return turnId.isNull() ? Optional.empty() : Optional.of(TurnId.valueOf(turnId.textValue()));
    }
}
