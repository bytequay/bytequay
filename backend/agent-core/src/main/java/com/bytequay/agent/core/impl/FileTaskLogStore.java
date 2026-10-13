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
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.toIntExact;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

public final class FileTaskLogStore
        implements TaskLogStore
{
    private static final int DEFAULT_MAX_BLOB_BYTES = 10 * 1024 * 1024;
    private static final int HISTORY_MAX_EVENTS = 1000;
    private static final int HISTORY_MAX_BYTES = AgentEventEnvelope.MAX_SERIALIZED_BYTES;
    private static final int READ_BLOCK_BYTES = 8192;

    private final AgentResourceService resourceService;
    private final int maxEventBytes;
    private final int maxBlobBytes;
    private final ObjectMapper objectMapper;
    private final DirectorySync directorySync;
    private final ConcurrentMap<TaskId, Object> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, Long> lastSequences = new ConcurrentHashMap<>();

    public FileTaskLogStore(AgentResourceService resourceService)
    {
        this(resourceService, AgentEventEnvelope.MAX_SERIALIZED_BYTES, DEFAULT_MAX_BLOB_BYTES);
    }

    public FileTaskLogStore(AgentResourceService resourceService, int maxEventBytes, int maxBlobBytes)
    {
        this(resourceService, maxEventBytes, maxBlobBytes, FileTaskLogStore::forceDirectory);
    }

    FileTaskLogStore(AgentResourceService resourceService, int maxEventBytes, int maxBlobBytes, DirectorySync directorySync)
    {
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        checkArgument(maxEventBytes > 0 && maxBlobBytes > 0, "Log size limits must be positive");
        this.maxEventBytes = maxEventBytes;
        this.maxBlobBytes = maxBlobBytes;
        this.directorySync = requireNonNull(directorySync, "directorySync is null");
        this.objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void append(AgentEventEnvelope envelope)
    {
        appendAll(ImmutableList.of(requireNonNull(envelope, "envelope is null")));
    }

    /** A display event costs a write, not a disk flush: flushing each one serialized a turn's streamed output behind the disk. */
    @Override
    public void appendWithoutFlush(AgentEventEnvelope envelope)
    {
        append(ImmutableList.of(requireNonNull(envelope, "envelope is null")), false);
    }

    /**
     * Appends a contiguous batch of records for one task under a single durable
     * sync. Tests that build long fixture logs use this instead of paying one
     * fsync per record through {@link #append}.
     */
    void appendAll(List<AgentEventEnvelope> envelopes)
    {
        append(envelopes, true);
    }

    private void append(List<AgentEventEnvelope> envelopes, boolean flush)
    {
        requireNonNull(envelopes, "envelopes is null");
        if (envelopes.isEmpty()) {
            return;
        }
        TaskId taskId = requireNonNull(envelopes.getFirst(), "envelope is null").taskId();
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        for (AgentEventEnvelope envelope : envelopes) {
            requireNonNull(envelope, "envelope is null");
            checkArgument(taskId.equals(envelope.taskId()), "Batch spans multiple tasks");
            byte[] json = serialize(envelope);
            if (json.length > maxEventBytes) {
                throw new AgentStorageException("Event exceeds the configured size limit");
            }
            batch.write(json, 0, json.length);
            batch.write('\n');
        }

        synchronized (lock(taskId)) {
            Long cachedSequence = lastSequences.get(taskId);
            long lastSequence = cachedSequence == null ? recoverTail(taskId) : cachedSequence;
            lastSequences.putIfAbsent(taskId, lastSequence);
            for (AgentEventEnvelope envelope : envelopes) {
                if (envelope.sequence() != lastSequence + 1) {
                    throw new AgentStorageException("Expected event sequence " + (lastSequence + 1) + " but received " + envelope.sequence());
                }
                lastSequence = envelope.sequence();
            }
            Path eventsFile = resourceService.require(taskId).eventsFile();
            long originalSize = -1;
            ByteBuffer lines = ByteBuffer.wrap(batch.toByteArray());
            try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                originalSize = channel.size();
                while (lines.hasRemaining()) {
                    channel.write(lines);
                }
                if (flush) {
                    channel.force(true);
                }
                lastSequences.put(taskId, lastSequence);
            }
            catch (IOException e) {
                lastSequences.remove(taskId);
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
    public AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(before, "before is null");
        requireNonNull(after, "after is null");
        if (before.isPresent() && after.isPresent()) {
            throw new InvalidAgentRequestException("Use either before or after, not both");
        }
        synchronized (lock(taskId)) {
            Path eventsFile = resourceService.require(taskId).eventsFile();
            try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.READ)) {
                long completeEnd = completeEnd(channel);
                long cursor = before.orElse(after.orElse(completeEnd));
                validateHistoryCursor(channel, cursor, completeEnd);
                long latestSequence = completeEnd == 0 ? 0 : previousRecord(channel, taskId, completeEnd).envelope().sequence();
                ArrayDeque<AgentEventEnvelope> events = new ArrayDeque<>();
                long start = cursor;
                long end = cursor;
                int bytes = 0;
                long expected = after.isPresent() && cursor > 0 ? previousRecord(channel, taskId, cursor).envelope().sequence() + 1 : 1;
                while (events.size() < HISTORY_MAX_EVENTS && (after.isPresent() ? end < completeEnd : start > 0)) {
                    StoredRecord record = after.isPresent() ? nextRecord(channel, taskId, end, completeEnd) : previousRecord(channel, taskId, start);
                    if (record.byteSize() > HISTORY_MAX_BYTES) {
                        throw new AgentStorageException("Task log record exceeds the history page size limit");
                    }
                    if (bytes + record.byteSize() > HISTORY_MAX_BYTES) {
                        break;
                    }
                    if (after.isPresent()) {
                        validateSequence(taskId, record.envelope(), expected++);
                        events.addLast(record.envelope());
                        end = record.end();
                    }
                    else {
                        if (!events.isEmpty()) {
                            validateSequence(taskId, record.envelope(), events.getFirst().sequence() - 1);
                        }
                        if (record.start() == 0) {
                            validateSequence(taskId, record.envelope(), 1);
                        }
                        events.addFirst(record.envelope());
                        start = record.start();
                    }
                    bytes += record.byteSize();
                }
                return new AgentHistoryPage(
                        ImmutableList.copyOf(events),
                        start > 0 ? OptionalLong.of(start) : OptionalLong.empty(),
                        end < completeEnd ? OptionalLong.of(end) : OptionalLong.empty(),
                        latestSequence);
            }
            catch (IOException e) {
                throw new AgentStorageException("Cannot read task history", e);
            }
        }
    }

    @Override
    public Optional<AgentEventEnvelope> findLatest(TaskId taskId, Predicate<AgentEventEnvelope> matches, int maxRecords)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(matches, "matches is null");
        checkArgument(maxRecords > 0, "maxRecords is not positive");
        synchronized (lock(taskId)) {
            Path eventsFile = resourceService.require(taskId).eventsFile();
            if (!Files.exists(eventsFile, NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.READ)) {
                long end = completeEnd(channel);
                for (int visited = 0; end > 0 && visited < maxRecords; visited++) {
                    StoredRecord record = previousRecord(channel, taskId, end);
                    if (matches.test(record.envelope())) {
                        return Optional.of(record.envelope());
                    }
                    end = record.start();
                }
                return Optional.empty();
            }
            catch (IOException e) {
                throw new AgentStorageException("Cannot read task history", e);
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
            try {
                try (FileChannel channel = FileChannel.open(blob, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    FileAgentResourceService.setOwnerFilePermissions(blob);
                    channel.force(true);
                }
                directorySync.force(blobsDirectory);
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

    private static void forceDirectory(Path directory)
            throws IOException
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface DirectorySync
    {
        void force(Path directory)
                throws IOException;
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
        Path eventsFile = resourceService.require(taskId).eventsFile();
        long sequence = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(eventsFile))) {
            byte[] line;
            while ((line = readCompleteLine(input)) != null) {
                validateSequence(taskId, deserialize(line), ++sequence);
            }
            return sequence;
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot validate task log", e);
        }
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
        return findLastNewline(channel, position, 0);
    }

    private static long findLastNewline(FileChannel channel, long position, long lowerBound)
            throws IOException
    {
        ByteBuffer block = ByteBuffer.allocate(READ_BLOCK_BYTES);
        long end = position + 1;
        while (end > lowerBound) {
            long start = Math.max(lowerBound, end - READ_BLOCK_BYTES);
            block.clear().limit(toIntExact(end - start));
            readFully(channel, block, start);
            for (int index = block.limit() - 1; index >= 0; index--) {
                if (block.get(index) == '\n') {
                    return start + index;
                }
            }
            end = start;
        }
        return -1;
    }

    private List<AgentEventEnvelope> readCompleteRecords(TaskId taskId, long afterSequence, int limit)
    {
        Path eventsFile = resourceService.require(taskId).eventsFile();
        ImmutableList.Builder<AgentEventEnvelope> result = ImmutableList.builderWithExpectedSize(Math.min(limit, 100));
        try (FileChannel channel = FileChannel.open(eventsFile, StandardOpenOption.READ)) {
            long end = completeEnd(channel);
            long position = afterSequence == 0 ? 0 : findAfter(channel, taskId, afterSequence, limit, end);
            long expectedSequence = afterSequence;
            while (position < end && limit-- > 0) {
                StoredRecord record = nextRecord(channel, taskId, position, end);
                validateSequence(taskId, record.envelope(), ++expectedSequence);
                result.add(record.envelope());
                position = record.end();
            }
            return result.build();
        }
        catch (IOException e) {
            throw new AgentStorageException("Cannot read task log", e);
        }
    }

    private long findAfter(FileChannel channel, TaskId taskId, long sequence, int limit, long end)
            throws IOException
    {
        if (end == 0) {
            return 0;
        }
        StoredRecord last = previousRecord(channel, taskId, end);
        if (sequence >= last.envelope().sequence()) {
            return end;
        }
        // Live subscriptions are normally at the tail; do not probe old output to deliver a new event.
        if (last.envelope().sequence() - sequence <= limit) {
            while (last.envelope().sequence() > sequence + 1 && last.start() > 0) {
                StoredRecord previous = previousRecord(channel, taskId, last.start());
                validateSequence(taskId, previous.envelope(), last.envelope().sequence() - 1);
                last = previous;
            }
            return last.start();
        }
        long low = 0;
        long high = end;
        while (low < high) {
            long middle = low + (high - low) / 2;
            long start = recordStart(channel, middle);
            StoredRecord record = nextRecord(channel, taskId, start, end);
            if (record.envelope().sequence() <= sequence) {
                low = record.end();
            }
            else {
                high = start;
            }
        }
        return low;
    }

    private long recordStart(FileChannel channel, long position)
            throws IOException
    {
        long lowerBound = Math.max(0, position - maxEventBytes - 1L);
        long newline = findLastNewline(channel, position - 1, lowerBound);
        if (newline < 0 && lowerBound > 0) {
            throw new AgentStorageException("Task log record exceeds the configured size limit");
        }
        return newline + 1;
    }

    private StoredRecord previousRecord(FileChannel channel, TaskId taskId, long end)
            throws IOException
    {
        return record(channel, taskId, recordStart(channel, end - 1), end);
    }

    private StoredRecord nextRecord(FileChannel channel, TaskId taskId, long start, long completeEnd)
            throws IOException
    {
        ByteBuffer block = ByteBuffer.allocate(READ_BLOCK_BYTES);
        long limit = Math.min(completeEnd, start + maxEventBytes + 1L);
        for (long position = start; position < limit; position += block.limit()) {
            block.clear().limit(toIntExact(Math.min(READ_BLOCK_BYTES, limit - position)));
            readFully(channel, block, position);
            for (int index = 0; index < block.limit(); index++) {
                if (block.get(index) == '\n') {
                    return record(channel, taskId, start, position + index + 1);
                }
            }
        }
        throw new AgentStorageException("Task log record exceeds the configured size limit");
    }

    private StoredRecord record(FileChannel channel, TaskId taskId, long start, long end)
            throws IOException
    {
        long size = end - start - 1;
        if (size < 0 || size > maxEventBytes) {
            throw new AgentStorageException("Task log record exceeds the configured size limit");
        }
        ByteBuffer bytes = ByteBuffer.allocate(toIntExact(size));
        readFully(channel, bytes, start);
        AgentEventEnvelope envelope = deserialize(bytes.array());
        if (!envelope.taskId().equals(taskId)) {
            throw new AgentStorageException("Task log sequence is corrupt");
        }
        return new StoredRecord(start, end, envelope);
    }

    private static long completeEnd(FileChannel channel)
            throws IOException
    {
        long size = channel.size();
        return size == 0 ? 0 : findLastNewline(channel, size - 1) + 1;
    }

    private static void validateHistoryCursor(FileChannel channel, long cursor, long completeEnd)
            throws IOException
    {
        if (cursor < 0 || cursor > completeEnd) {
            throw new InvalidAgentRequestException("History cursor is outside the complete task log");
        }
        if (cursor > 0) {
            ByteBuffer preceding = ByteBuffer.allocate(1);
            readFully(channel, preceding, cursor - 1);
            if (preceding.get(0) != '\n') {
                throw new InvalidAgentRequestException("History cursor is not a complete event boundary");
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer bytes, long position)
            throws IOException
    {
        while (bytes.hasRemaining()) {
            if (channel.read(bytes, position + bytes.position()) < 0) {
                throw new IOException("Task log changed during a read");
            }
        }
    }

    private record StoredRecord(long start, long end, AgentEventEnvelope envelope)
    {
        private int byteSize()
        {
            return toIntExact(end - start - 1);
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
        envelope.turnId().ifPresentOrElse(turnId -> root.put("turnId", turnId.toString()), () -> root.putNull("turnId"));
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
                case AgentEventEnvelope.CURRENT_SCHEMA_VERSION -> deserializeV2(root);
                default -> throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
            };
        }
        catch (IOException | RuntimeException e) {
            throw new AgentStorageException("Task log contains a corrupt record", e);
        }
    }

    private AgentEventEnvelope deserializeV2(JsonNode root)
            throws JsonProcessingException
    {
        AgentEventPayload.Kind kind = AgentEventPayload.Kind.valueOf(root.required("type").textValue().toUpperCase(Locale.ROOT));
        AgentEventPayload payload = objectMapper.treeToValue(root.required("payload"), kind.type());
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
