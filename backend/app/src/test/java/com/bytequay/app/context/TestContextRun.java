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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestContextRun
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final RepoRef REPO = RepoRef.of("owner", "repository");
    private static final String SHA = "a".repeat(40);
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.of("model"), Optional.of("medium"),
            AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of());

    @Test
    void separatesAnsweredQuestionsFromNotesAndUnfinishedWork()
    {
        ContextBuild.Result drafted = new ContextBuild.Result(ImmutableList.of(ready("knowledge:project:commit")));

        ContextRun answered = ContextRun.of(learning(ImmutableMap.of(ContextRun.PROGRESS, ContextRun.COMPLETED), Optional.of(drafted)), task(), finished(false));
        assertThat(answered.outcome()).isEqualTo(ContextRun.Outcome.ANSWERED);
        assertThat(answered.noteIds()).containsExactly("knowledge:project:commit");
        assertThat(answered.question().orElseThrow().topicTitle()).isEqualTo("Iceberg connector");
        assertThat(answered.endedAt()).contains(NOW);

        ContextRun noNote = ContextRun.of(learning(ImmutableMap.of(ContextRun.PROGRESS, ContextRun.COMPLETED),
                Optional.of(new ContextBuild.Result(ImmutableList.of()))), task(), finished(false));
        assertThat(noNote.outcome()).isEqualTo(ContextRun.Outcome.NO_NOTE);

        ImmutableMap.Builder<String, String> operations = ImmutableMap.builder();
        for (int index = 0; index < 250; index++) {
            operations.put("read-operation:" + index, "Reserved");
        }
        for (int index = 0; index < 20; index++) {
            operations.put("document-open:" + index, "Reserved");
        }
        ContextRun unfinished = ContextRun.of(learning(operations.put(ContextRun.PROGRESS, ContextRun.UNFINISHED).buildOrThrow(), Optional.empty()), task(), finished(false));
        assertThat(unfinished.outcome()).isEqualTo(ContextRun.Outcome.UNFINISHED);
        assertThat(unfinished.sourceOperations()).isEqualTo(250);
        assertThat(unfinished.documentOpens()).isEqualTo(20);

        assertThat(ContextRun.of(learning(ImmutableMap.of(ContextRun.PROGRESS, ContextRun.UNFINISHED), Optional.empty()), task(), finished(false)).outcome())
                .isEqualTo(ContextRun.Outcome.UNFINISHED);
        assertThat(ContextRun.of(learning(ImmutableMap.of(ContextRun.PROGRESS, ContextRun.UNFINISHED), Optional.empty()), task(), finished(true)).outcome())
                .isEqualTo(ContextRun.Outcome.STOPPED);
        ContextRun failed = ContextRun.of(learning(ImmutableMap.of(), Optional.empty()),
                Optional.of(task().orElseThrow().fail("The Codex CLI needs sign-in", NOW, NOW.plusSeconds(60))), Optional.empty());
        assertThat(failed.outcome()).isEqualTo(ContextRun.Outcome.FAILED);
        assertThat(failed.detail()).contains("The Codex CLI needs sign-in");
        assertThat(ContextRun.of(learning(ImmutableMap.of(), Optional.empty()), task(), Optional.of(running())).outcome())
                .isEqualTo(ContextRun.Outcome.RUNNING);
    }

    @Test
    void retainsTheLimitOutcomeOfARunStoppedAtThePreviousCeiling()
    {
        ImmutableMap.Builder<String, String> operations = ImmutableMap.builder();
        for (int index = 0; index < 40; index++) {
            operations.put("read-operation:" + index, "Reserved");
        }
        ContextBuild build = learning(operations.buildOrThrow(), Optional.empty());
        ContextBuild limited = build.withCapture(build.capture().logged(NOW,
                "Reached 40 of 40 source operations · no further reads this run", ContextBuild.Activity.Tone.LIMIT));

        ContextRun run = ContextRun.of(limited, task(), finished(false));

        assertThat(run.sourceOperations()).isEqualTo(40);
        assertThat(run.outcome()).isEqualTo(ContextRun.Outcome.LIMIT);
    }

    @Test
    void discoveryIsSubmittedOnlyWithASavedReport()
    {
        ContextBuild discovery = new ContextBuild(2, "discover", "sha256:" + "b".repeat(64), "workspace", TASK.toString(),
                new RepositoryIdentity("owner", "repository"), 1, REPO, SHA, "branch", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), 0, NOW,
                new ContextBuild.Capture(ImmutableList.of(), ImmutableMap.of(), ImmutableMap.of("discovery:1", "LIST core"), ImmutableMap.of(),
                        Optional.of(new ContextBuild.Result(ImmutableList.of())), ImmutableList.of(), ImmutableList.of()),
                ContextBuild.Mode.DISCOVER, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        ContextRun run = ContextRun.of(discovery, task(), finished(false));

        assertThat(run.outcome()).isEqualTo(ContextRun.Outcome.SUBMITTED);
        assertThat(run.sourceOperations()).isEqualTo(1);
        assertThat(run.question()).isEmpty();
    }

    @Test
    void anInvestigationSettlesWithItsNoteUnlessItKeptTheCurrentText()
    {
        ContextBuild.Investigation investigation = new ContextBuild.Investigation("knowledge:project", 3, "project", "Snapshots", "Reads reuse the snapshot", "Brief");
        Optional<ContextBuild.Result> settled = Optional.of(new ContextBuild.Result(ImmutableList.of()));

        ContextRun rewritten = ContextRun.of(investigating(investigation, ImmutableMap.of(ContextRun.SETTLED, "REWRITE"), settled), task(), finished(false));
        assertThat(rewritten.outcome()).isEqualTo(ContextRun.Outcome.SETTLED);
        assertThat(rewritten.noteIds()).containsExactly("knowledge:project");
        assertThat(rewritten.investigation()).contains(new ContextRun.Investigated("knowledge:project", "Snapshots", "Reads reuse the snapshot", Optional.of("REWRITE")));
        assertThat(ContextRun.of(investigating(investigation, ImmutableMap.of(ContextRun.SETTLED, "KEEP_CURRENT"), settled), task(), finished(false)).noteIds()).isEmpty();

        ContextRun unsettled = ContextRun.of(investigating(investigation, ImmutableMap.of(), Optional.empty()), task(), finished(false));
        assertThat(unsettled.outcome()).isEqualTo(ContextRun.Outcome.FAILED);
        assertThat(unsettled.investigation().orElseThrow().outcome()).isEmpty();
    }

    @Test
    void aRunThatCorrectedItsQuestionSaysSo()
    {
        ContextRun run = ContextRun.of(learning(ImmutableMap.of(ContextRun.PROGRESS, ContextRun.CORRECTED), Optional.of(new ContextBuild.Result(ImmutableList.of()))),
                task(), finished(false));
        assertThat(run.outcome()).isEqualTo(ContextRun.Outcome.CORRECTED);
    }

    private static ContextBuild investigating(ContextBuild.Investigation investigation, Map<String, String> attempts, Optional<ContextBuild.Result> result)
    {
        return new ContextBuild(4, "investigate", "sha256:" + "b".repeat(64), "workspace", TASK.toString(),
                new RepositoryIdentity("owner", "repository"), 1, REPO, SHA, "branch", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of("project"), ImmutableList.of(), ImmutableList.of(), 0, NOW,
                new ContextBuild.Capture(ImmutableList.of(), ImmutableMap.of(), attempts, ImmutableMap.of(), result, ImmutableList.of(), ImmutableList.of()),
                ContextBuild.Mode.LEARN, Optional.empty(), Optional.empty(), Optional.of(investigation), Optional.empty());
    }

    private static ContextBuild learning(Map<String, String> attempts, Optional<ContextBuild.Result> result)
    {
        return new ContextBuild(3, "learn", "sha256:" + "b".repeat(64), "workspace", TASK.toString(),
                new RepositoryIdentity("owner", "repository"), 1, REPO, SHA, "branch", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of("project"), ImmutableList.of(), ImmutableList.of(), 0, NOW,
                new ContextBuild.Capture(ImmutableList.of(), ImmutableMap.of(), attempts, ImmutableMap.of(), result, ImmutableList.of(), ImmutableList.of()),
                ContextBuild.Mode.LEARN, Optional.of(new ContextBuild.Assignment("plan", "topic:1", "Iceberg connector", 0,
                        "How are table snapshots committed?", "", ImmutableList.of(), ImmutableList.of())), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ContextPreparationResult ready(String noteId)
    {
        ContextRepositoryRead read = new ContextRepositoryRead("context-read:1", new ContextReference.RepositoryFile(1, REPO, "README.md", SHA), SHA,
                NOW, 1, 1, 1, 6, false, Optional.of("evidence"));
        return new ContextPreparationResult("attempt", ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", SHA), ImmutableList.of()),
                ContextPreparationResult.Outcome.READY, ImmutableList.of(noteId), ImmutableList.of(read), "Drafted");
    }

    private static Optional<AgentTask> task()
    {
        return Optional.of(AgentTask.created(TASK, Optional.of("workspace"), false, "Learn", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repository"), Path.of("/tmp/context-run"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW));
    }

    private static AgentTurn running()
    {
        return AgentTurn.accepted(TurnId.random(), TASK, "context-build:" + TASK, SETTINGS, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty());
    }

    private static Optional<AgentTurn> finished(boolean cancelled)
    {
        AgentTurn turn = cancelled ? running().requestCancellation(NOW) : running();
        return Optional.of(turn.finish(cancelled ? TurnState.CANCELLED : TurnState.COMPLETED, AgentMetrics.empty(), cancelled ? Optional.of("Stopped") : Optional.empty(), NOW));
    }
}
