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
import com.bytequay.app.service.agents.AgentVerdictFile;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestConflictRepairAgent
{
    private final WorkspaceEngineSettings engines = mock(WorkspaceEngineSettings.class);
    private final AiDefaultsService aiDefaults = mock(AiDefaultsService.class);
    private final CliReviewRunner cli = mock(CliReviewRunner.class);
    private final ConflictRepairAgent agent =
            new ConflictRepairAgent(cli, engines, aiDefaults, new ObjectMapper());

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
    void anApiEngineIsRefusedRatherThanSilentlyDoingNothing()
    {
        when(engines.forAudience("ws-1", SessionAudience.CI_FIX))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(
                        new WorkModel(WorkModelKind.API, "anthropic", null, null), true)));

        // An in-JVM turn has no shell and no editor, so it cannot do this job.
        assertThatThrownBy(() -> agent.repair(
                Path.of("/tmp"), "ws-1", "Pick", List.of(), null, 1_000, null))
                .hasMessageContaining("needs a CLI agent");
    }

    @Test
    void aTurnThatNeverRanSaysSoRatherThanBlamingTheVerdict()
    {
        // A missing binary, a refused login, a rejected flag. The runner already
        // knows why; reporting "no verdict" sent the reader looking for a model
        // that never spoke.
        when(engines.forAudience(any(), any())).thenReturn(Optional.empty());
        when(aiDefaults.get()).thenReturn(new AiDefaultsService.AiDefaults(
                null, null, null, null, null, null, null));
        when(cli.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CliReviewRunner.Result(
                        "", null, 0, "ERRORED",
                        "CLI agent exited with code 127: codex: command not found", null));

        ConflictRepairAdvisor.Outcome outcome = agent.repair(
                Path.of("/tmp"), "ws-1", "Pick", List.of(), null, 1_000, null);

        assertThat(outcome.resolved()).isFalse();
        assertThat(outcome.detail())
                .contains("did not run")
                .contains("command not found");
    }

    @Test
    void eachVerdictStatusMapsToWhatTheRunDoesNext()
    {
        assertThat(outcome("resolved", "kept the fork's prefix").resolved()).isTrue();
        assertThat(outcome("resolved", "kept the fork's prefix").validated()).isTrue();
        // Not a failure — but the run log has to say the range was taken on trust.
        assertThat(outcome("resolved_unvalidated", "no wrapper here").resolved()).isTrue();
        assertThat(outcome("resolved_unvalidated", "no wrapper here").validated()).isFalse();
        assertThat(outcome("parked", "upstream dropped the setter").resolved()).isFalse();
    }

    @Test
    void anUnknownStatusParksAndNamesWhatWasWritten()
    {
        ConflictRepairAdvisor.Outcome outcome = outcome("done", "all good");

        assertThat(outcome.resolved()).isFalse();
        assertThat(outcome.detail()).contains("unknown verdict status").contains("done");
    }

    private ConflictRepairAdvisor.Outcome outcome(String status, String summary)
    {
        return agent.outcomeOf(
                new AgentVerdictFile.Verdict(status, summary), null, 120, "session-9");
    }
}
