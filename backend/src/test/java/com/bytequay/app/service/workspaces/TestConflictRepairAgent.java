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
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestConflictRepairAgent
{
    private final WorkModelResolver engines = mock(WorkModelResolver.class);
    private final CliReviewRunner cli = mock(CliReviewRunner.class);
    private final ConflictRepairAgent agent =
            new ConflictRepairAgent(cli, engines, new ObjectMapper());

    private void engine(WorkModel model)
    {
        when(engines.resolveForWorkspace(any(), any())).thenReturn(
                new WorkModelResolver.Resolved(model, new WorkModelResolver.Provenance(
                        WorkModelResolver.Source.GLOBAL_DEFAULT, null, "test")));
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
        engine(new WorkModel(WorkModelKind.API, "anthropic", null, null));

        // An in-JVM turn has no shell and no editor, so it cannot do this job.
        assertThatThrownBy(() -> agent.repair(
                Path.of("/tmp"), "ws-1", "Pick", List.of(), null, 1_000, null, null))
                .hasMessageContaining("needs a CLI agent");
    }

    @Test
    void aTurnThatNeverRanSaysSoRatherThanBlamingTheVerdict()
    {
        // A missing binary, a refused login, a rejected flag. The runner already
        // knows why; reporting "no verdict" sent the reader looking for a model
        // that never spoke.
        engine(new WorkModel(WorkModelKind.CLI, "codex", null, null));
        when(cli.run(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new CliReviewRunner.Result(
                        "", null, 0, "ERRORED",
                        "CLI agent exited with code 127: codex: command not found", null));

        ConflictRepairAdvisor.Outcome outcome = agent.repair(
                Path.of("/tmp"), "ws-1", "Pick", List.of(), null, 1_000, null, null);

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
