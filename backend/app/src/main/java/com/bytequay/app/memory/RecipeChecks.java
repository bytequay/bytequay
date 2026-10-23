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
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.review.MavenCommands;
import com.bytequay.app.utils.ContextText;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Flags a recipe when a later run of its command fails: at the end of each turn, the turn's failed commands are compared with the
 * workspace's recipes, without a model, and a failure reports the recipe, so it shows under Needs you until the user keeps, edits or
 * deletes it.
 */
@Component
public class RecipeChecks
{
    // ponytail: reads the turn's last three history pages, so a turn with more events can miss an early failure; page further if that matters.
    private static final int MAX_PAGES = 3;
    private static final Logger log = LoggerFactory.getLogger(RecipeChecks.class);

    private final MemoryService memories;
    private final AgentEventService events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("recipe-checks-", 0).factory());

    public RecipeChecks(MemoryService memories, AgentEventService events)
    {
        this.memories = requireNonNull(memories, "memories is null");
        this.events = requireNonNull(events, "events is null");
    }

    /** Checks off the provider's thread, so a turn settles without waiting for it. */
    public void finished(AgentTask task, AgentTurn turn)
    {
        executor.execute(() -> {
            try {
                check(task, turn);
            }
            catch (RuntimeException failure) {
                log.warn("Recipes were not checked against turn {}", turn.id(), failure);
            }
        });
    }

    void check(AgentTask task, AgentTurn turn)
    {
        Optional<String> workspaceId = task.workspaceId();
        if (workspaceId.isEmpty()) {
            return;
        }
        List<Memory> recipes = memories.list(workspaceId.orElseThrow()).stream().filter(memory -> memory.kind() == Memory.Kind.RECIPE).collect(toImmutableList());
        if (recipes.isEmpty()) {
            return;
        }
        List<AgentEventEnvelope> failed = failedCommands(task.id(), turn.id());
        for (Memory recipe : recipes) {
            for (MemorySource source : recipe.sources()) {
                Optional<MemorySource.CommandPart> part = source.command();
                // Only a run after the one the recipe rests on counts; the turn that found the command may have failed on the way.
                Optional<AgentEventEnvelope> later = part.flatMap(command -> failed.stream()
                        .filter(envelope -> envelope.occurredAt().isAfter(source.at()) && runs(envelope, command)).findFirst());
                if (later.isPresent()) {
                    report(workspaceId.orElseThrow(), recipe, part.orElseThrow(), later.orElseThrow(), task.id());
                }
            }
        }
    }

    private void report(String workspaceId, Memory recipe, MemorySource.CommandPart command, AgentEventEnvelope run, TaskId taskId)
    {
        int exit = ((AgentEventPayload.CommandActivity) run.payload()).exitCode().orElseThrow();
        String reason = "Its command failed with exit " + exit + " in a later run";
        if (recipe.reports().stream().noneMatch(report -> report.taskId().equals(taskId.toString()) && report.reason().equals(reason))) {
            memories.report(workspaceId, recipe.id(), reason, ContextText.excerpt(command.command(), MemorySource.MAX_QUOTE_BYTES), taskId.toString());
        }
    }

    private static boolean runs(AgentEventEnvelope envelope, MemorySource.CommandPart command)
    {
        AgentEventPayload.CommandActivity activity = (AgentEventPayload.CommandActivity) envelope.payload();
        return MavenCommands.typedCommand(activity.argv()).equals(command.command())
                && activity.cwd().filter(directory -> !directory.isBlank()).orElse(".").equals(command.directory());
    }

    /** The turn's commands that finished with a nonzero exit code, read back from the newest history page until events of an earlier turn. */
    private List<AgentEventEnvelope> failedCommands(TaskId taskId, TurnId turnId)
    {
        List<AgentEventEnvelope> failed = new ArrayList<>();
        OptionalLong before = OptionalLong.empty();
        for (int page = 0; page < MAX_PAGES; page++) {
            AgentHistoryPage history = events.readHistory(taskId, before, OptionalLong.empty());
            history.events().stream().filter(envelope -> envelope.turnId().filter(turnId::equals).isPresent() && failed(envelope)).forEach(failed::add);
            boolean earlier = history.events().stream().anyMatch(envelope -> envelope.turnId().filter(id -> !id.equals(turnId)).isPresent());
            if (earlier || history.beforeCursor().isEmpty()) {
                break;
            }
            before = history.beforeCursor();
        }
        return failed;
    }

    private static boolean failed(AgentEventEnvelope envelope)
    {
        return envelope.payload() instanceof AgentEventPayload.CommandActivity command
                && (command.state() == ProviderAgentEvent.ActivityState.COMPLETED || command.state() == ProviderAgentEvent.ActivityState.FAILED)
                && command.exitCode().filter(code -> code != 0).isPresent() && !command.background();
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
