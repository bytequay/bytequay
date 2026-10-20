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
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.review.ReviewToolAssignment;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestMemorySources
{
    private static final String HEAD = "c".repeat(40);

    private final TaskId taskId = TaskId.random();
    // A task's worktree directory is named after the task.
    private final Path worktree = Path.of("/tmp/memory-sources").resolve(taskId.toString());
    private final TurnId turnId = TurnId.random();
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final ReviewToolAssignment assignments = mock(ReviewToolAssignment.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final MemorySources sources = new MemorySources(turns, events, logs, reviews, assignments, git);
    private final List<AgentEventEnvelope> log = new ArrayList<>();
    private final AgentTask task = AgentTask.created(taskId, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI,
            new TaskWorktree(taskId, "workspace", new RepositoryIdentity("trinodb", "trino"), worktree, "bytequay/task/" + taskId, "a".repeat(40)),
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);

    @BeforeEach
    void setUp()
    {
        turn("turn-1");
        when(events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty())).thenAnswer(ignored -> new AgentHistoryPage(log, OptionalLong.empty(),
                OptionalLong.empty(), log.size()));
        when(logs.findLatest(eq(taskId), any(), anyInt())).thenAnswer(call -> {
            Predicate<AgentEventEnvelope> matches = call.getArgument(1);
            return ImmutableList.copyOf(log).reverse().stream().filter(matches).findFirst();
        });
        when(git.run(worktree, "rev-parse", "--verify", "HEAD^{commit}")).thenReturn(new GitCommandRunner.Result(0, HEAD + "\n", false));
    }

    @Test
    void bindsARuleToTheUsersWordsInTheTurnIncludingASteer()
    {
        message("Please fix the parser.", AgentEventPayload.DeliveryKind.TURN);
        message("Also, keep refactors in their own commits from now on.", AgentEventPayload.DeliveryKind.STEER);

        MemorySource quoted = sources.userWords(task, turnId, Optional.of("keep refactors in their own   commits"));
        assertThat(quoted.quote()).isEqualTo("keep refactors in their own   commits");
        assertThat(quoted.user()).contains(new MemorySource.UserPart(MemorySource.Where.TASK_MESSAGE, taskId.toString(), log.get(1).eventId().toString()));
        assertThat(sources.userWords(task, turnId, Optional.empty()).quote()).isEqualTo("Please fix the parser.");
        assertThatThrownBy(() -> sources.userWords(task, turnId, Optional.of("squash everything"))).hasMessageContaining("the user did not write these words");
        assertThatThrownBy(() -> sources.userWords(task, TurnId.random(), Optional.empty())).hasMessage("This tool belongs to a stopped or replaced turn");

        turn("local-review:abc");
        assertThatThrownBy(() -> sources.userWords(task, turnId, Optional.empty())).hasMessage(MemorySources.NO_WORDS);
    }

    @Test
    void bindsAReviewRuleToTheDiscussionQuestionAndNeverToAReviewStep()
    {
        AgentReview review = pullRequestReview("review", "workspace", "trinodb/trino", 7, taskId.toString(), NOW);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review));
        ReviewMessage question = new ReviewMessage("message-1", "review", Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.USER,
                "We never log user input here; please don't suggest it.", 1, "run-1", Optional.empty(), Optional.empty(), 0, NOW);
        ReviewRun run = new ReviewRun("run-1", "review", 1, ReviewRun.Scope.FULL, "main", "a".repeat(40), "a".repeat(40), "b".repeat(40), Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(),
                false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(assignments.resolve("review", Optional.of(turnId))).thenReturn(new ReviewToolAssignment.Binding(run, Optional.empty(), Optional.of(question)));

        MemorySource words = sources.userWords(task, turnId, Optional.empty());
        assertThat(words.quote()).isEqualTo("We never log user input here; please don't suggest it.");
        assertThat(words.user()).contains(new MemorySource.UserPart(MemorySource.Where.REVIEW_MESSAGE, "review", "message-1"));

        turn("review-step:run-1:1");
        assertThatThrownBy(() -> sources.userWords(task, turnId, Optional.empty())).hasMessage(MemorySources.NO_WORDS);
    }

    @Test
    void bindsARecipeToTheNewestRunOfTheCommandThatPassed()
    {
        command("/bin/zsh -lc './mvnw -pl core  test -Dtest=TestParser'", 1, "core");
        command("/bin/zsh -lc './mvnw -pl core test -Dtest=TestParser'", 0, "core");
        command("./mvnw -pl web test", 2, "");

        MemorySource passed = sources.passingCommand(task, turnId, "./mvnw -pl core test -Dtest=TestParser");
        assertThat(passed.command()).contains(new MemorySource.CommandPart(taskId.toString(), turnId.toString(), "./mvnw -pl core test -Dtest=TestParser", "core", HEAD));
        assertThat(passed.quote()).isEqualTo("./mvnw -pl core test -Dtest=TestParser");
        assertThatThrownBy(() -> sources.passingCommand(task, turnId, "./mvnw -pl web test")).hasMessageContaining("no run of this command finished with exit code 0");
    }

    private void turn(String key)
    {
        AgentTurn turn = AgentTurn.accepted(turnId, taskId, key, new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofHours(1), ImmutableList.of(), Optional.empty(), Optional.empty()), NOW);
        when(turns.findActive(taskId)).thenReturn(Optional.of(turn));
    }

    private void message(String text, AgentEventPayload.DeliveryKind kind)
    {
        log.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), log.size() + 1, taskId, Optional.of(turnId), root(), NOW,
                new AgentEventPayload.UserMessage(text, ImmutableList.of(), kind)));
    }

    private void command(String command, int exitCode, String directory)
    {
        log.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), log.size() + 1, taskId, Optional.of(turnId), root(), NOW,
                new AgentEventPayload.CommandActivity(exitCode == 0 ? ProviderAgentEvent.ActivityState.COMPLETED : ProviderAgentEvent.ActivityState.FAILED,
                        ImmutableList.of(command), Optional.empty(), Optional.of(exitCode), false, Optional.of(directory).filter(value -> !value.isEmpty()), Optional.empty(),
                        ImmutableList.of(), false,
                        Optional.empty())));
    }
}
