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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderUsageSample;
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
        List<ProviderAgentEvent> events = ImmutableList.of(
                new ProviderAgentEvent.Message(
                        Optional.of("message-1"),
                        Optional.of(providerTime),
                        ProviderAgentEvent.MessagePhase.FINAL,
                        "done",
                        Optional.of("parent-1")),
                new ProviderAgentEvent.Plan(
                        Optional.of("plan-1"),
                        Optional.of(providerTime),
                        ImmutableList.of(new ProviderAgentEvent.PlanItem(
                                "verify",
                                ProviderAgentEvent.PlanItemState.IN_PROGRESS))),
                new ProviderAgentEvent.Tool(
                        Optional.of("tool-1"),
                        Optional.of(providerTime),
                        "call-1",
                        "Read",
                        ProviderAgentEvent.ActivityState.COMPLETED,
                        "read a file",
                        Optional.empty()),
                new ProviderAgentEvent.Command(
                        Optional.of("command-1"),
                        Optional.of(providerTime),
                        "process-1",
                        ProviderAgentEvent.ActivityState.COMPLETED,
                        ImmutableList.of("git", "status"),
                        Optional.of("clean"),
                        Optional.of(0),
                        false),
                new ProviderAgentEvent.FileChange(
                        Optional.of("file-1"),
                        Optional.of(providerTime),
                        "README.md",
                        ProviderAgentEvent.ChangeKind.MODIFIED,
                        Optional.of("diff")),
                new ProviderAgentEvent.Approval(
                        Optional.of("approval-1"),
                        Optional.of(providerTime),
                        "request-1",
                        "Run tests",
                        "mvn verify"),
                new ProviderAgentEvent.Usage(new ProviderUsageSample(
                        Optional.of("usage-1"),
                        ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                        10,
                        2,
                        4,
                        Optional.empty(),
                        CostProvenance.UNAVAILABLE,
                        providerTime)),
                new ProviderAgentEvent.Lifecycle(
                        Optional.of("lifecycle-1"),
                        Optional.of(providerTime),
                        ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED,
                        "complete"),
                new ProviderAgentEvent.Error(
                        Optional.of("error-1"),
                        Optional.of(providerTime),
                        "failed",
                        "failure",
                        false));
        ImmutableList.Builder<AgentEventEnvelope> expected = ImmutableList.builder();
        long sequence = 0;
        for (ProviderAgentEvent event : events) {
            AgentEventEnvelope envelope = envelope(++sequence, event);
            store.append(envelope);
            expected.add(envelope);
        }
        List<AgentEventEnvelope> expectedEvents = expected.build();

        assertThat(store.readAfter(taskId, 0, 20)).containsExactlyElementsOf(expectedEvents);
        assertThat(store.readAfter(taskId, 8, 1)).containsExactly(expectedEvents.getLast());
        assertThat(new FileTaskLogStore(resources).lastSequence(taskId)).isEqualTo(9);
        assertThatThrownBy(() -> store.append(envelope(11, events.getFirst())))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("Expected event sequence 10");
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
        Instant providerTime = Instant.parse("2026-08-16T00:00:00Z");
        List<ProviderAgentEvent> events = ImmutableList.of(
                new ProviderAgentEvent.Message(
                        Optional.of("message-1"),
                        Optional.of(providerTime),
                        ProviderAgentEvent.MessagePhase.PARTIAL,
                        "hello from",
                        Optional.empty()),
                new ProviderAgentEvent.Message(
                        Optional.of("message-1"),
                        Optional.of(providerTime),
                        ProviderAgentEvent.MessagePhase.FINAL,
                        "hello from ByteQuay Codex",
                        Optional.empty()),
                new ProviderAgentEvent.Usage(new ProviderUsageSample(
                        Optional.of("usage-1"),
                        ProviderUsageSample.Semantics.CUMULATIVE,
                        16_286,
                        9_984,
                        11,
                        Optional.empty(),
                        CostProvenance.UNAVAILABLE,
                        providerTime)),
                new ProviderAgentEvent.Lifecycle(
                        Optional.of("turn-1"),
                        Optional.of(providerTime),
                        ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED,
                        "completed"));
        ImmutableList.Builder<AgentEventEnvelope> expected = ImmutableList.builder();
        long sequence = 0;
        for (ProviderAgentEvent event : events) {
            AgentEventEnvelope envelope = envelope(++sequence, event);
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
        assertThat(stored).contains("\"schemaVersion\":1");
        Files.writeString(
                eventsFile,
                stored.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> store.readAfter(taskId, 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasRootCauseMessage("Unsupported schemaVersion: 2");
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
        assertThatThrownBy(() -> store.writeBlob(taskId, ByteBuffer.wrap(new byte[9]), "application/octet-stream"))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("size limit");
    }

    private AgentEventEnvelope envelope(long sequence, ProviderAgentEvent event)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                sequence,
                taskId,
                Optional.empty(),
                Instant.parse("2026-08-16T00:00:01Z").plusSeconds(sequence),
                event);
    }

    private static ProviderAgentEvent message(String text)
    {
        return new ProviderAgentEvent.Message(
                Optional.empty(),
                Optional.empty(),
                ProviderAgentEvent.MessagePhase.FINAL,
                text,
                Optional.empty());
    }
}
