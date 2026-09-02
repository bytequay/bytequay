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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.CostProvenance;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Instant providerTime = Instant.parse("2026-08-16T00:00:00Z");
        AgentEventSource source = new AgentEventSource(
                Optional.of("thread-1"),
                Optional.empty(),
                Optional.of("root/reviewer"),
                Optional.of("provider-turn-1"),
                Optional.of("item-1"),
                Optional.of("provider-event-1"),
                Optional.of(providerTime));
        List<AgentEventPayload> payloads = ImmutableList.of(
                new AgentEventPayload.UserMessage(
                        "review this",
                        ImmutableList.of(new AgentAttachmentReference("attachment-1", "diagram.png", "image/png")),
                        AgentEventPayload.DeliveryKind.TURN),
                new AgentEventPayload.AssistantMessage(
                        AgentEventPayload.MessageKind.ANSWER,
                        AgentEventPayload.MessagePhase.FINAL,
                        "done"),
                new AgentEventPayload.PlanUpdated(ImmutableList.of(new AgentEventPayload.PlanItem(
                        "verify",
                        AgentEventPayload.PlanItemState.IN_PROGRESS))),
                new AgentEventPayload.ToolActivity(
                        "Read",
                        AgentEventPayload.ActivityState.COMPLETED,
                        "read a file"),
                new AgentEventPayload.CommandActivity(
                        AgentEventPayload.ActivityState.COMPLETED,
                        ImmutableList.of("git", "status"),
                        Optional.of("clean"),
                        Optional.of(0),
                        false),
                new AgentEventPayload.FileActivity(
                        AgentEventPayload.ActivityState.COMPLETED,
                        "README.md",
                        AgentEventPayload.ChangeKind.MODIFIED,
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
                        AgentEventPayload.ApprovalOutcome.ALLOWED),
                new AgentEventPayload.UsageUpdated(
                        AgentEventPayload.UsageSemantics.FINAL_FOR_INVOCATION,
                        10,
                        2,
                        4,
                        Optional.empty(),
                        CostProvenance.UNAVAILABLE),
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
                        "complete"),
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
        assertThat(store.readAfter(taskId, 10, 1)).containsExactly(expectedEvents.getLast());
        assertThat(new FileTaskLogStore(resources).lastSequence(taskId)).isEqualTo(11);
        assertThatThrownBy(() -> store.append(envelope(13, source, payloads.getFirst())))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("Expected event sequence 12");
    }

    @Test
    void findsSequenceByStableEventId()
    {
        EventId target = EventId.random();
        store.append(envelope(EventId.random(), 1, message("first")));
        store.append(envelope(target, 2, message("second")));

        assertThat(store.findSequence(taskId, target)).contains(2L);
        assertThat(store.findSequence(taskId, EventId.random())).isEmpty();
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
                        AgentEventPayload.MessageKind.ANSWER,
                        AgentEventPayload.MessagePhase.PARTIAL,
                        "hello from"),
                new AgentEventPayload.AssistantMessage(
                        AgentEventPayload.MessageKind.ANSWER,
                        AgentEventPayload.MessagePhase.FINAL,
                        "hello from ByteQuay Codex"),
                new AgentEventPayload.UsageUpdated(
                        AgentEventPayload.UsageSemantics.CUMULATIVE,
                        16_286,
                        9_984,
                        11,
                        Optional.empty(),
                        CostProvenance.UNAVAILABLE),
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
    void readsLegacyV1AsCanonicalRootEventAndContinuesWithV2()
            throws IOException
    {
        String legacy = """
                {"schemaVersion":1,"sequence":1,"taskId":"%s","turnId":null,"occurredAt":"2026-08-16T00:00:02Z","type":"MESSAGE","event":{"providerEventId":"legacy-message","providerTimestamp":null,"phase":"FINAL","text":"legacy response","parentToolUseId":null}}
                """.formatted(taskId);
        Files.writeString(resources.require(taskId).eventsFile(), legacy, StandardOpenOption.TRUNCATE_EXISTING);

        AgentEventEnvelope expectedLegacy = new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.legacy(taskId, 1),
                1,
                taskId,
                Optional.empty(),
                new AgentEventSource(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("legacy-message"),
                        Optional.empty()),
                Instant.parse("2026-08-16T00:00:02Z"),
                message("legacy response"));
        assertThat(store.readAfter(taskId, 0, 10)).containsExactly(expectedLegacy);

        AgentEventEnvelope second = envelope(2, message("new response"));
        store.append(second);
        assertThat(store.readAfter(taskId, 0, 10)).containsExactly(expectedLegacy, second);
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
    void readsLegacyApprovalAsNonActionableHistory()
            throws IOException
    {
        String legacy = """
                {"schemaVersion":1,"sequence":1,"taskId":"%s","turnId":null,"occurredAt":"2026-08-16T00:00:02Z","type":"APPROVAL","event":{"providerEventId":"legacy-approval-event","providerTimestamp":null,"approvalId":"legacy-approval","title":"Run command","detail":"git status"}}
                """.formatted(taskId);
        Files.writeString(resources.require(taskId).eventsFile(), legacy, StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(store.readAfter(taskId, 0, 10)).singleElement().satisfies(envelope -> {
            assertThat(envelope.source().providerItemId()).isEmpty();
            assertThat(envelope.payload()).isInstanceOfSatisfying(
                    AgentEventPayload.ApprovalRequested.class,
                    approval -> {
                        assertThat(approval.approvalId()).isEqualTo("legacy-approval");
                        assertThat(approval.actionable()).isFalse();
                        assertThat(approval.availableDecisions()).isEmpty();
                    });
        });
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
                EventId.legacy(taskId, sequence),
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
                AgentEventPayload.MessageKind.ANSWER,
                AgentEventPayload.MessagePhase.FINAL,
                text);
    }
}
