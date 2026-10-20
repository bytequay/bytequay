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
package com.bytequay.app.memory;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRecipeChecks
{
    private static final String COMMAND = "./mvnw -pl core test -Dtest=TestParser";

    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final MemoryService memories = new MemoryService(store, new InMemoryAppSettingsStore(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final AgentEventService events = mock(AgentEventService.class);
    private final RecipeChecks checks = new RecipeChecks(memories, events);
    private final List<AgentEventEnvelope> log = new ArrayList<>();
    private final AgentTask task = AgentTask.created(taskId, Optional.of("workspace"), true, "Development", new AgentLaneId("codex"),
            new RepositoryIdentity("trinodb", "trino"), Path.of("/tmp/recipe-checks"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    private final AgentTurn turn = AgentTurn.accepted(turnId, taskId, "turn", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofHours(1), ImmutableList.of(), Optional.empty(), Optional.empty()), NOW);

    @Test
    void aLaterFailedRunOfTheCommandFlagsTheRecipeOncePerTask()
    {
        store.save(new Memory("m7", "workspace", Memory.Kind.RECIPE, "Parser test", "Run the parser test with `" + COMMAND + "`.", ImmutableList.of("core/"),
                ImmutableList.of(MemorySource.command(COMMAND, NOW, new MemorySource.CommandPart("earlier-task", "earlier-turn", COMMAND, "core", "a".repeat(40)))),
                new Memory.Origin(false, "Remembered in a task", Optional.of("earlier-task")), 1, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW));
        when(events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty())).thenAnswer(ignored -> new AgentHistoryPage(log, OptionalLong.empty(),
                OptionalLong.empty(), log.size()));
        command("/bin/zsh -lc '" + COMMAND + "'", 1, "core", NOW.minusSeconds(60));
        command(COMMAND, 1, "web", NOW.plusSeconds(60));
        command("./mvnw -pl core verify", 1, "core", NOW.plusSeconds(60));

        checks.check(task, turn);
        assertThat(store.find("m7").orElseThrow().reports()).isEmpty();

        command("/bin/zsh -lc '" + COMMAND + "'", 2, "core", NOW.plusSeconds(120));
        checks.check(task, turn);
        checks.check(task, turn);
        assertThat(store.find("m7").orElseThrow().reports()).singleElement().satisfies(report -> {
            assertThat(report.reason()).isEqualTo("Its command failed with exit 2 in a later run");
            assertThat(report.quote()).isEqualTo(COMMAND);
            assertThat(report.taskId()).isEqualTo(taskId.toString());
        });
    }

    private void command(String command, int exitCode, String directory, Instant at)
    {
        log.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), log.size() + 1, taskId, Optional.of(turnId), root(), at,
                new AgentEventPayload.CommandActivity(ProviderAgentEvent.ActivityState.FAILED, ImmutableList.of(command), Optional.empty(), Optional.of(exitCode), false,
                        Optional.of(directory), Optional.empty(), ImmutableList.of(), false, Optional.empty())));
    }
}
