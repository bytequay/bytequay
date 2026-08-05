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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestConflictRepairAgent
{
    private final WorkspaceEngineSettings engines = mock(WorkspaceEngineSettings.class);
    private final AiDefaultsService aiDefaults = mock(AiDefaultsService.class);
    private final ConflictRepairAgent agent = new ConflictRepairAgent(
            mock(TurnRunner.class), mock(ReviewProviderEndpoints.class),
            mock(AppSettingsStore.class), new ObjectMapper(),
            mock(CliReviewRunner.class), engines, aiDefaults);

    @Test
    void theWorkspacesOwnCiFixEngineWins()
    {
        when(engines.forAudience("ws-1", SessionAudience.CI_FIX))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(
                        new WorkModel(WorkModelKind.CLI, "claude-code", null, null), true)));

        WorkModel engine = agent.engineFor("ws-1");

        assertThat(engine.kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(engine.agentOrProvider()).isEqualTo("claude-code");
    }

    @Test
    void theAccountDefaultAnswersWhenTheWorkspaceHasNoPick()
    {
        when(engines.forAudience("ws-1", SessionAudience.CI_FIX)).thenReturn(Optional.empty());
        when(aiDefaults.get()).thenReturn(new AiDefaultsService.AiDefaults(
                "cli:claude-code", "cli:claude-code", "cli:claude-code",
                "cli:claude-code", "cli:codex", "cli:claude-code", "cli:claude-code"));

        WorkModel engine = agent.engineFor("ws-1");

        assertThat(engine.kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(engine.agentOrProvider()).isEqualTo("codex");
    }

    @Test
    void anUnconfiguredWorkspaceLandsOnACliAgentAndNeverAnApiKey()
    {
        when(engines.forAudience("ws-1", SessionAudience.CI_FIX)).thenReturn(Optional.empty());
        when(aiDefaults.get()).thenReturn(new AiDefaultsService.AiDefaults(
                null, null, null, null, null, null, null));

        WorkModel engine = agent.engineFor("ws-1");

        // The old behaviour fell through to OpenAI here and failed for want of
        // a key nobody had asked for.
        assertThat(engine.kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(engine.agentOrProvider()).isEqualTo("codex");
    }

    @Test
    void bothCliAgentSpellingsResolve()
    {
        assertThat(ConflictRepairAgent.cliProvider("claude-code"))
                .isEqualTo(CliReviewRunner.Provider.CLAUDE);
        assertThat(ConflictRepairAgent.cliProvider("codex"))
                .isEqualTo(CliReviewRunner.Provider.CODEX);
        assertThatThrownBy(() -> ConflictRepairAgent.cliProvider("gpt-5"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aProposalIsParsedWithItsSessionSoTheNextTurnResumesIt()
    {
        ConflictRepairAdvisor.Repair repair = agent.parse("""
                ```json
                {"edits":[{"path":"A.java","find":"old","replace":"new"}],
                 "rationale":"kept the fork's binding","needs_human":false}
                ```
                """, 120, "session-9");

        assertThat(repair.edits()).containsExactly(
                new ConflictRepairAdvisor.Edit("A.java", "old", "new"));
        assertThat(repair.rationale()).isEqualTo("kept the fork's binding");
        assertThat(repair.sessionId()).isEqualTo("session-9");
        assertThat(repair.costMilliUsd()).isEqualTo(120);
    }

    @Test
    void askingForAHumanIsAnEmptyProposalRatherThanAGuess()
    {
        ConflictRepairAdvisor.Repair repair = agent.parse(
                "{\"edits\":[],\"rationale\":\"this needs a decision\",\"needs_human\":true}",
                80, null);

        assertThat(repair.isEmpty()).isTrue();
        assertThat(repair.rationale()).isEqualTo("this needs a decision");
    }

    @Test
    void validationRefusesAnythingTheProgramCannotApplySafely(@TempDir Path worktree)
            throws Exception
    {
        Files.writeString(worktree.resolve("A.java"), "line\nrepeat\nrepeat\n",
                StandardCharsets.UTF_8);
        List<String> conflicted = List.of("A.java");

        // Not one of this pick's conflicted files.
        assertThatThrownBy(() -> agent.validated(
                repair(new ConflictRepairAdvisor.Edit("B.java", "line", "x")), worktree, conflicted))
                .hasMessageContaining("outside the conflicted files");
        // An anchor that matches twice would edit the wrong one.
        assertThatThrownBy(() -> agent.validated(
                repair(new ConflictRepairAdvisor.Edit("A.java", "repeat", "x")), worktree, conflicted))
                .hasMessageContaining("not unique");
        // An anchor that matches nothing.
        assertThatThrownBy(() -> agent.validated(
                repair(new ConflictRepairAdvisor.Edit("A.java", "absent", "x")), worktree, conflicted))
                .hasMessageContaining("anchor is not in");
        // A "repair" that leaves the markers behind.
        assertThatThrownBy(() -> agent.validated(
                repair(new ConflictRepairAdvisor.Edit("A.java", "line", "<<<<<<< HEAD")),
                worktree, conflicted))
                .hasMessageContaining("conflict markers");

        assertThat(agent.validated(
                repair(new ConflictRepairAdvisor.Edit("A.java", "line", "kept")),
                worktree, conflicted).edits())
                .hasSize(1);
    }

    private static ConflictRepairAdvisor.Repair repair(ConflictRepairAdvisor.Edit edit)
    {
        return new ConflictRepairAdvisor.Repair(List.of(edit), "why", 10, null);
    }
}
