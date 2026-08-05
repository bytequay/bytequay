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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestHarnessRepairAgent
{
    private final HarnessRepairAgent agent = new HarnessRepairAgent(
            mock(CliReviewRunner.class),
            mock(WorkspaceEngineSettings.class),
            mock(AiDefaultsService.class));

    @Test
    void aCommittedRoundIsTheOnlyVerdictThatGetsPushed()
    {
        assertThat(agent.read("COMMITTED: fixed the compile break", 300, "s-1").committed())
                .isTrue();
        assertThat(agent.read("NOTHING: both failures are cloud-gated", 300, "s-1").committed())
                .isFalse();
        assertThat(agent.read("PARKED: needs a call on the fork's API", 300, "s-1").committed())
                .isFalse();
        // A turn that ends any other way is never assumed to have worked — the
        // program will not publish a tree whose author never called it finished.
        assertThat(agent.read("Let me start by reading the log.", 300, "s-1").committed())
                .isFalse();
    }

    @Test
    void nothingToDoIsDistinctFromBeingStuck()
    {
        HarnessRepairAgent.Outcome nothing =
                agent.read("NOTHING: both failures are cloud-gated", 300, "s-1");
        HarnessRepairAgent.Outcome parked =
                agent.read("PARKED: needs a human call", 300, "s-1");

        assertThat(nothing.nothing()).isTrue();
        assertThat(nothing.detail()).isEqualTo("both failures are cloud-gated");
        assertThat(parked.nothing()).isFalse();
    }

    @Test
    void theVerdictIsTheLastLineAndCarriesTheSessionForward()
    {
        HarnessRepairAgent.Outcome outcome = agent.read("""
                Looked at the build failure first — the fork's config key was dropped.
                Committed a fixup on "Add retry budget".
                COMMITTED: restored the fork's config key on its own pick's fixup
                """, 450, "session-7");

        assertThat(outcome.detail())
                .isEqualTo("restored the fork's config key on its own pick's fixup");
        assertThat(outcome.sessionId()).isEqualTo("session-7");
        assertThat(outcome.costMilliUsd()).isEqualTo(450);
    }

    @Test
    void aResumedRoundAsksWhetherLastRoundsFixWorked()
    {
        String resumed = HarnessRepairAgent.userPrompt(List.of(failure("boom")), null, true);
        String first = HarnessRepairAgent.userPrompt(List.of(failure("boom")), null, false);

        assertThat(resumed).contains("did what you changed last round work?");
        assertThat(first).doesNotContain("last round");
    }

    @Test
    void aTruncatedRoundSaysSoRatherThanLookingComplete()
    {
        List<Failure> many = IntStream.range(0, 60)
                .mapToObj(i -> failure("failure " + i))
                .toList();

        String prompt = HarnessRepairAgent.userPrompt(many, null, false);

        // Silent truncation would read as "that is all of it" to the agent.
        assertThat(prompt).contains("60 failure(s) this round").contains("are shown");
    }

    private static Failure failure(String signature)
    {
        return new Failure(
                signature, "cycle", "run", 7L, "build", "core", null, null,
                signature, signature, "build", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
    }
}
