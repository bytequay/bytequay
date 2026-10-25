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
import com.bytequay.agent.core.AgentEventJson;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThat;

/** Run from backend with: mvn -pl :agent-core -Dtest=TestFileTaskLogStoreBenchmark -Dbytequay.agent.log-benchmark=true test */
@EnabledIfSystemProperty(named = "bytequay.agent.log-benchmark", matches = "true")
class TestFileTaskLogStoreBenchmark
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void measuresLogValidationHistoryAndReplay()
            throws IOException
    {
        int count = 10_000;
        String text = "x".repeat(4096);
        FileAgentResourceService resources = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        TaskId taskId = TaskId.random();
        Path file = resources.allocate(taskId).eventsFile();
        try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(file))) {
            for (int sequence = 1; sequence <= count; sequence++) {
                AgentEventEnvelope event = new AgentEventEnvelope(
                        AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                        EventId.random(),
                        sequence,
                        taskId,
                        Optional.empty(),
                        root(),
                        Instant.EPOCH,
                        new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.PARTIAL, text));
                output.write(AgentEventJson.write(event));
                output.write('\n');
            }
        }
        FileTaskLogStore store = new FileTaskLogStore(resources);
        System.out.printf("Agent log benchmark: %,d events, %,d text bytes/event, %,d file bytes%n", count, text.length(), Files.size(file));
        assertThat(store.readAfter(taskId, count - 1, 1).getFirst().payload())
                .isEqualTo(new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.PARTIAL, text));

        measure("full validation", () -> store.lastSequence(taskId), count, 7);
        measure("latest history", () -> store.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).events().getLast().sequence(), count, 15);
        measure("first history", () -> store.readHistory(taskId, OptionalLong.empty(), OptionalLong.of(0)).events().getFirst().sequence(), 1, 15);
        measure("midpoint replay (1000 events)", () -> store.readAfter(taskId, count / 2, 1000).size(), 1000, 15);
        measure("live tail (1 event)", () -> store.readAfter(taskId, count - 1, 1).getFirst().sequence(), count, 15);
    }

    private static void measure(String name, LongSupplier operation, long expected, int iterations)
    {
        for (int warmup = 0; warmup < 3; warmup++) {
            assertThat(operation.getAsLong()).isEqualTo(expected);
        }
        double[] milliseconds = new double[iterations];
        for (int iteration = 0; iteration < iterations; iteration++) {
            long start = System.nanoTime();
            long result = operation.getAsLong();
            milliseconds[iteration] = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(result).isEqualTo(expected);
        }
        Arrays.sort(milliseconds);
        System.out.printf("%s: median %.3f ms, min %.3f ms, max %.3f ms%n", name, milliseconds[iterations / 2], milliseconds[0], milliseconds[iterations - 1]);
    }
}
