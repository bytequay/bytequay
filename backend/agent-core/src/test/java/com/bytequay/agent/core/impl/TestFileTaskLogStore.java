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

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEvent.ChangeKind;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandAction;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandActionKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEvent.PlanItem;
import com.bytequay.agent.spi.ProviderAgentEvent.PlanItemState;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentEventSource.root;
import static java.lang.Math.toIntExact;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TestFileTaskLogStore
{
    @TempDir
    private Path temporaryDirectory;

    private FileAgentResourceService resources;
    private FileTaskLogStore store;
    private TaskId taskId;

    @BeforeEach
    void setUp()
    {
        resources = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        store = new FileTaskLogStore(resources, 4096, 8);
        taskId = TaskId.random();
        resources.allocate(taskId);
    }

    @Test
    void flushesAndReadsEveryEventTypeInSequence()
    {
        AgentEventSource source = new AgentEventSource(
                Optional.of("thread-1"),
                Optional.empty(),
                Optional.of("root/reviewer"),
                Optional.of("provider-turn-1"),
                Optional.of("item-1"),
                Optional.of("provider-event-1"));
        List<AgentEventPayload> payloads = ImmutableList.of(
                new AgentEventPayload.UserMessage(
                        "review this",
                        ImmutableList.of(new AgentAttachmentReference("attachment-1", "diagram.png", "image/png")),
                        AgentEventPayload.DeliveryKind.TURN),
                new AgentEventPayload.AssistantMessage(
                        MessageKind.ANSWER,
                        MessagePhase.FINAL,
                        "done"),
                new AgentEventPayload.PlanUpdated(ImmutableList.of(new PlanItem(
                        "verify",
                        PlanItemState.IN_PROGRESS))),
                new AgentEventPayload.ToolActivity(
                        "Read",
                        ActivityState.COMPLETED,
                        "read a file",
                        Optional.empty()),
                new AgentEventPayload.ToolActivity(
                        "bytequay/review_get_context",
                        ActivityState.COMPLETED,
                        "context-receipt:context-search:" + "a".repeat(64),
                        Optional.of(12L),
                        Optional.of("Searched notes for “checkpoint” · 3 notes")),
                new AgentEventPayload.CommandActivity(
                        ActivityState.COMPLETED,
                        ImmutableList.of("git", "status"),
                        Optional.of("clean"),
                        Optional.of(0),
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        ImmutableList.of(),
                        false,
                        Optional.empty()),
                new AgentEventPayload.FileActivity(
                        ActivityState.COMPLETED,
                        "README.md",
                        ChangeKind.MODIFIED,
                        Optional.of("diff")),
                new AgentEventPayload.ApprovalRequested(
                        "request-1",
                        AgentEventPayload.ApprovalKind.COMMAND,
                        "Run tests",
                        "mvn verify",
                        ImmutableList.of("run: mvn verify"),
                        true,
                        ImmutableList.of(AgentEventPayload.ApprovalOption.ALLOW_ONCE, AgentEventPayload.ApprovalOption.DENY)),
                new AgentEventPayload.ApprovalResolved(
                        "request-1",
                        AgentEventPayload.ApprovalOutcome.ALLOWED,
                        false),
                new AgentEventPayload.UsageUpdated(
                        AgentEventPayload.UsageSemantics.FINAL_FOR_INVOCATION,
                        10,
                        2,
                        4,
                        Optional.empty()),
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
                        "complete"),
                new AgentEventPayload.DevelopmentResult(false, Optional.of("Which implementation should I use?"), Optional.empty()),
                new AgentEventPayload.Error(
                        "failed",
                        "failure",
                        false));
        ImmutableList.Builder<AgentEventEnvelope> expected = ImmutableList.builder();
        long sequence = 0;
        for (AgentEventPayload payload : payloads) {
            AgentEventEnvelope envelope = envelope(++sequence, source, payload);
            store.append(envelope);
            expected.add(envelope);
        }
        List<AgentEventEnvelope> expectedEvents = expected.build();

        assertThat(store.readAfter(taskId, 0, 20)).containsExactlyElementsOf(expectedEvents);
        assertThat(store.readAfter(taskId, expectedEvents.size() - 1, 1)).containsExactly(expectedEvents.getLast());
        assertThat(new FileTaskLogStore(resources).lastSequence(taskId)).isEqualTo(expectedEvents.size());
        assertThatThrownBy(() -> store.append(envelope(expectedEvents.size() + 2, source, payloads.getFirst())))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("Expected event sequence " + (expectedEvents.size() + 1));
    }

    @Test
    void replaysCommandNamesAndOlderActionsWithoutNames()
            throws IOException
    {
        AgentEventEnvelope named = envelope(1, new AgentEventPayload.CommandActivity(
                ActivityState.COMPLETED,
                ImmutableList.of("cat SKILL.md"),
                Optional.empty(),
                Optional.of(0),
                false,
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(new CommandAction(
                        CommandActionKind.READ,
                        Optional.of("OpenAI Docs skill"),
                        Optional.empty(),
                        Optional.empty())),
                false,
                Optional.empty()));
        store.append(named);
        assertThat(store.readAfter(taskId, 0, 10)).containsExactly(named);

        Path eventsFile = resources.require(taskId).eventsFile();
        Files.writeString(eventsFile, Files.readString(eventsFile).replace("\"name\":\"OpenAI Docs skill\",", ""));
        AgentEventPayload.CommandActivity older = (AgentEventPayload.CommandActivity) store.readAfter(taskId, 0, 10).getFirst().payload();
        assertThat(older.actions().getFirst().name()).isEmpty();
    }

    @Test
    void ignoresOnlyAnUnterminatedFinalRecord()
            throws IOException
    {
        AgentEventEnvelope first = envelope(1, message("complete"));
        store.append(first);
        Files.writeString(
                resources.require(taskId).eventsFile(),
                "{partial",
                StandardOpenOption.APPEND);

        assertThat(store.readAfter(taskId, 0, 10)).containsExactly(first);

        TaskId corruptTask = TaskId.random();
        TaskResources corruptResources = resources.allocate(corruptTask);
        Files.writeString(corruptResources.eventsFile(), "{}\n{partial", StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.readAfter(corruptTask, 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("corrupt record");
    }

    @Test
    void roundTripsPartialFinalAndCumulativeUsage()
    {
        List<AgentEventPayload> payloads = ImmutableList.of(
                new AgentEventPayload.AssistantMessage(
                        MessageKind.ANSWER,
                        MessagePhase.PARTIAL,
                        "hello from"),
                new AgentEventPayload.AssistantMessage(
                        MessageKind.ANSWER,
                        MessagePhase.FINAL,
                        "hello from ByteQuay Codex"),
                new AgentEventPayload.UsageUpdated(
                        AgentEventPayload.UsageSemantics.CUMULATIVE,
                        16_286,
                        9_984,
                        11,
                        Optional.empty()),
                new AgentEventPayload.UsageUpdated(
                        AgentEventPayload.UsageSemantics.CUMULATIVE_FOR_INVOCATION,
                        20_759,
                        20_224,
                        73,
                        Optional.empty()),
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
                        "completed"));
        ImmutableList.Builder<AgentEventEnvelope> expected = ImmutableList.builder();
        long sequence = 0;
        for (AgentEventPayload payload : payloads) {
            AgentEventEnvelope envelope = envelope(++sequence, root(), payload);
            store.append(envelope);
            expected.add(envelope);
        }

        assertThat(store.readAfter(taskId, 0, 10)).containsExactlyElementsOf(expected.build());
    }

    @Test
    void truncatesCrashTailBeforeNextAppend()
            throws IOException
    {
        AgentEventEnvelope first = envelope(1, message("first"));
        store.append(first);
        Files.writeString(resources.require(taskId).eventsFile(), "{partial", StandardOpenOption.APPEND);

        FileTaskLogStore restarted = new FileTaskLogStore(resources, 4096, 8);
        assertThat(restarted.lastSequence(taskId)).isEqualTo(1);
        AgentEventEnvelope second = envelope(2, message("second"));
        restarted.append(second);

        assertThat(restarted.readAfter(taskId, 0, 10)).containsExactly(first, second);
    }

    @Test
    void rejectsUnsupportedSchemaVersion()
            throws IOException
    {
        store.append(envelope(1, message("complete")));
        Path eventsFile = resources.require(taskId).eventsFile();
        String stored = Files.readString(eventsFile);
        assertThat(stored).contains("\"schemaVersion\":2");
        Files.writeString(
                eventsFile,
                stored.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> store.readAfter(taskId, 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasRootCauseMessage("Unsupported schemaVersion: 3");
    }

    @Test
    void toleratesAdditionalV2FieldsAndRejectsUnknownType()
            throws IOException
    {
        AgentEventEnvelope expected = envelope(1, message("complete"));
        store.append(expected);
        Path eventsFile = resources.require(taskId).eventsFile();
        String stored = Files.readString(eventsFile);
        Files.writeString(
                eventsFile,
                stored
                        .replace("\"occurredAt\"", "\"futureEnvelopeField\":true,\"occurredAt\"")
                        .replace("\"text\":\"complete\"", "\"text\":\"complete\",\"futurePayloadField\":true"),
                StandardOpenOption.TRUNCATE_EXISTING);
        assertThat(store.readAfter(taskId, 0, 10)).containsExactly(expected);

        Files.writeString(
                eventsFile,
                stored.replace("\"type\":\"assistant_message\"", "\"type\":\"future_event\""),
                StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.readAfter(taskId, 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedPersistedRecord()
            throws IOException
    {
        Path eventsFile = resources.require(taskId).eventsFile();
        Files.write(eventsFile, new byte[4097], StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(eventsFile, "\n", StandardOpenOption.APPEND);

        assertThatThrownBy(() -> store.readAfter(taskId, 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void findsTheNewestMatchingRecordWithinTheBound()
    {
        assertThat(store.findLatest(taskId, event -> true, 10)).isEmpty();

        ImmutableList.Builder<AgentEventEnvelope> fixture = ImmutableList.builder();
        fixture.add(envelope(1, message("the answer")));
        for (int sequence = 2; sequence <= 30; sequence++) {
            fixture.add(envelope(sequence, new AgentEventPayload.UserMessage("steer " + sequence, ImmutableList.of(), AgentEventPayload.DeliveryKind.STEER)));
        }
        store.appendAll(fixture.build());

        assertThat(store.findLatest(taskId, event -> event.payload() instanceof AgentEventPayload.AssistantMessage, 30))
                .map(AgentEventEnvelope::sequence)
                .contains(1L);
        // The bound keeps a long run of other records from turning a summary into a full scan.
        assertThat(store.findLatest(taskId, event -> event.payload() instanceof AgentEventPayload.AssistantMessage, 29)).isEmpty();
        assertThat(store.findLatest(taskId, event -> true, 1)).map(AgentEventEnvelope::sequence).contains(30L);
    }

    @Test
    void pagesHistoryInBothDirectionsWithoutSplittingEvents()
    {
        ImmutableList.Builder<AgentEventEnvelope> fixture = ImmutableList.builder();
        for (int sequence = 1; sequence <= 2250; sequence++) {
            fixture.add(envelope(sequence, message("event " + sequence)));
        }
        store.appendAll(fixture.build());

        AgentHistoryPage latest = store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty());
        assertThat(latest.events()).hasSize(1000);
        assertThat(latest.events().getFirst().sequence()).isEqualTo(1251);
        assertThat(latest.events().getLast().sequence()).isEqualTo(2250);
        assertThat(latest.latestSequence()).isEqualTo(2250);
        assertThat(latest.afterCursor()).isEmpty();

        AgentHistoryPage older = store.readHistory(taskId, latest.beforeCursor(), OptionalLong.empty());
        assertThat(older.events()).hasSize(1000);
        assertThat(older.events().getFirst().sequence()).isEqualTo(251);
        assertThat(older.events().getLast().sequence()).isEqualTo(1250);
        AgentHistoryPage oldest = store.readHistory(taskId, older.beforeCursor(), OptionalLong.empty());
        assertThat(oldest.events()).hasSize(250);
        assertThat(oldest.events().getFirst().sequence()).isEqualTo(1);
        assertThat(oldest.beforeCursor()).isEmpty();

        AgentHistoryPage newer = store.readHistory(taskId, OptionalLong.empty(), oldest.afterCursor());
        assertThat(newer.events()).containsExactlyElementsOf(older.events());
        assertThat(newer.beforeCursor()).isEqualTo(older.beforeCursor());
        assertThat(newer.afterCursor()).isEqualTo(older.afterCursor());

        store.append(envelope(2251, message("appended after the page")));
        AgentHistoryPage sameOlder = store.readHistory(taskId, latest.beforeCursor(), OptionalLong.empty());
        assertThat(sameOlder.events()).containsExactlyElementsOf(older.events());
        assertThat(sameOlder.latestSequence()).isEqualTo(2251);
        assertThat(store.readAfter(taskId, 17, 2)).extracting(AgentEventEnvelope::sequence).containsExactly(18L, 19L);
        assertThat(store.readAfter(taskId, 2248, 8)).extracting(AgentEventEnvelope::sequence).containsExactly(2249L, 2250L, 2251L);
    }

    @Test
    void boundsHistoryBytesAndAllowsOneMaximumSizeEvent()
            throws IOException
    {
        store = new FileTaskLogStore(resources);
        AgentEventEnvelope first = envelope(1, message(""));
        store.append(first);
        Path file = resources.require(taskId).eventsFile();
        long firstSize = Files.size(file);
        int overhead = toIntExact(firstSize - 1);
        AgentEventEnvelope large = envelope(2, message("x".repeat(AgentEventEnvelope.MAX_SERIALIZED_BYTES - overhead)));
        store.append(large);
        assertThat(Files.size(file) - firstSize).isEqualTo(AgentEventEnvelope.MAX_SERIALIZED_BYTES + 1L);
        AgentEventEnvelope last = envelope(3, message("tail"));
        store.append(last);

        AgentHistoryPage latest = store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty());
        assertThat(latest.events()).containsExactly(last);
        AgentHistoryPage middle = store.readHistory(taskId, latest.beforeCursor(), OptionalLong.empty());
        assertThat(middle.events()).containsExactly(large);
        AgentHistoryPage oldest = store.readHistory(taskId, middle.beforeCursor(), OptionalLong.empty());
        assertThat(oldest.events()).containsExactly(first);
        assertThat(store.readHistory(taskId, OptionalLong.empty(), oldest.afterCursor()).events()).containsExactly(large);
        assertThat(store.readHistory(taskId, OptionalLong.empty(), middle.afterCursor()).events()).containsExactly(last);
        assertThat(store.readAfter(taskId, 1, 1)).containsExactly(large);
    }

    @Test
    void readsTheTailOfAGigabyteSparseLogWithoutVisitingItsPrefix()
            throws IOException
    {
        ImmutableList.Builder<AgentEventEnvelope> fixture = ImmutableList.builder();
        for (int sequence = 1; sequence <= 1001; sequence++) {
            fixture.add(envelope(sequence, message("bounded tail")));
        }
        store.appendAll(fixture.build());
        Path file = resources.require(taskId).eventsFile();
        byte[] tail = Files.readAllBytes(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(1024L * 1024 * 1024);
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
            ByteBuffer records = ByteBuffer.wrap(tail);
            while (records.hasRemaining()) {
                channel.write(records);
            }
        }

        // The sparse prefix is deliberately unreadable as JSON: success proves the page never visits it.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            AgentHistoryPage latest = store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty());
            assertThat(latest.events()).hasSize(1000);
            assertThat(latest.events().getFirst().sequence()).isEqualTo(2);
            assertThat(latest.latestSequence()).isEqualTo(1001);
            assertThat(latest.beforeCursor().orElseThrow()).isGreaterThan(1024L * 1024 * 1024);
            assertThat(store.readAfter(taskId, 1000, 1)).extracting(AgentEventEnvelope::sequence).containsExactly(1001L);
        });
    }

    @Test
    void ignoresTornHistoryTailWithoutChangingTheFileAndRejectsInvalidCursors()
            throws IOException
    {
        assertThat(store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).events()).isEmpty();
        AgentEventEnvelope first = envelope(1, message("complete"));
        store.append(first);
        Path file = resources.require(taskId).eventsFile();
        long completeEnd = Files.size(file);
        Files.writeString(file, "{partial", StandardOpenOption.APPEND);
        long tornSize = Files.size(file);
        AgentHistoryPage latest = store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty());
        assertThat(latest.events()).containsExactly(first);
        assertThat(latest.latestSequence()).isEqualTo(1);
        assertThat(Files.size(file)).isEqualTo(tornSize);
        assertThat(store.readHistory(taskId, OptionalLong.empty(), OptionalLong.of(completeEnd)).events()).isEmpty();
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.of(1), OptionalLong.empty())).isInstanceOf(InvalidAgentRequestException.class);
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.of(-1), OptionalLong.empty())).isInstanceOf(InvalidAgentRequestException.class);
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.of(tornSize), OptionalLong.empty())).isInstanceOf(InvalidAgentRequestException.class);
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.of(0), OptionalLong.of(0))).isInstanceOf(InvalidAgentRequestException.class);
    }

    @Test
    void rejectsCorruptHistoryRecordsAndStillValidatesTheWholeLogOnRecovery()
            throws IOException
    {
        ImmutableList.Builder<AgentEventEnvelope> fixture = ImmutableList.builder();
        for (int sequence = 1; sequence <= 1001; sequence++) {
            fixture.add(envelope(sequence, message("event")));
        }
        store.appendAll(fixture.build());
        Path file = resources.require(taskId).eventsFile();
        String valid = Files.readString(file);
        Files.writeString(file, valid.replaceFirst("\"sequence\":1,", "\"sequence\":9,"), StandardOpenOption.TRUNCATE_EXISTING);
        assertThat(store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).events()).hasSize(1000);
        assertThatThrownBy(() -> new FileTaskLogStore(resources).lastSequence(taskId))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("sequence is corrupt");
        Files.writeString(file, valid.replace("\"sequence\":1001,", "\"sequence\":1003,"), StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("sequence is corrupt");
        Files.writeString(file, "{}\n", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("corrupt record");
    }

    @Test
    void writesBoundedOwnerControlledBlobs()
            throws IOException
    {
        BlobRef blob = store.writeBlob(taskId, ByteBuffer.wrap(new byte[] {1, 2, 3}), "application/octet-stream");
        Path stored = resources.require(taskId).runtimeDirectory().resolve(blob.relativePath());

        assertThat(blob.size()).isEqualTo(3);
        assertThat(stored).hasBinaryContent(new byte[] {1, 2, 3});
        assertThat(store.readBlob(taskId, blob)).isEqualTo(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertThatThrownBy(() -> store.writeBlob(taskId, ByteBuffer.wrap(new byte[9]), "application/octet-stream"))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void removesBlobWhenParentDirectorySyncFails()
            throws IOException
    {
        Path blobsDirectory = resources.require(taskId).blobsDirectory();
        FileTaskLogStore failingStore = new FileTaskLogStore(resources, 4096, 8, directory -> {
            assertThat(directory).isEqualTo(blobsDirectory);
            try (var files = Files.list(directory)) {
                assertThat(files.count()).isEqualTo(1);
            }
            throw new IOException("directory sync failed");
        });

        assertThatThrownBy(() -> failingStore.writeBlob(taskId, ByteBuffer.wrap(new byte[] {1, 2, 3}), "application/octet-stream"))
                .isInstanceOf(AgentStorageException.class)
                .hasRootCauseMessage("directory sync failed");
        try (var files = Files.list(blobsDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeOrUnboundedBlobReads()
            throws IOException
    {
        BlobRef blob = store.writeBlob(taskId, ByteBuffer.wrap(new byte[] {1, 2, 3}), "application/octet-stream");

        assertThatThrownBy(() -> store.readBlob(taskId, new BlobRef(blob.relativePath(), blob.mediaType(), 2)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("size does not match");
        assertThatThrownBy(() -> store.readBlob(taskId, new BlobRef("events.jsonl", "application/json", 0)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("missing or unsafe");

        Path oversized = resources.require(taskId).blobsDirectory().resolve("oversized");
        Files.write(oversized, new byte[9]);
        assertThatThrownBy(() -> store.readBlob(taskId, new BlobRef("blobs/oversized", "application/octet-stream", 9)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("size limit");

        TaskId otherTask = TaskId.random();
        resources.allocate(otherTask);
        assertThatThrownBy(() -> store.readBlob(otherTask, blob))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("missing or unsafe");
    }

    private AgentEventEnvelope envelope(long sequence, AgentEventSource source, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.random(),
                sequence,
                taskId,
                Optional.empty(),
                source,
                Instant.parse("2026-08-16T00:00:01Z").plusSeconds(sequence),
                payload);
    }

    private AgentEventEnvelope envelope(long sequence, AgentEventPayload payload)
    {
        return envelope(sequence, root(), payload);
    }

    private AgentEventEnvelope envelope(EventId eventId, long sequence, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                eventId,
                sequence,
                taskId,
                Optional.empty(),
                root(),
                Instant.parse("2026-08-16T00:00:01Z").plusSeconds(sequence),
                payload);
    }

    private static AgentEventPayload message(String text)
    {
        return new AgentEventPayload.AssistantMessage(
                MessageKind.ANSWER,
                MessagePhase.FINAL,
                text);
    }
}
