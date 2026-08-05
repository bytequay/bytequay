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
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
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
            mock(AiDefaultsService.class),
            mock(SessionKnowledgeProvider.class));

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

    @Test
    void whatTheAgentLearnedIsLiftedOutForTheProgramToPersist()
    {
        HarnessRepairAgent.Outcome outcome = agent.read("""
                The plan fixtures were stale again.

                <learned title="stale plan fixtures after an optimizer change">
                Shows up as expected/but was on a generated plan file. Regenerating the
                fixtures fixes it; editing the assertion does not, because the next
                optimizer change reintroduces it.
                </learned>

                COMMITTED: regenerated the plan fixtures
                """, 300, "s-1");

        assertThat(outcome.learned()).hasSize(1);
        assertThat(outcome.learned().getFirst().title())
                .isEqualTo("stale plan fixtures after an optimizer change");
        // The "what didn't work and why" half is the part only the session that
        // tried it can write, so it has to survive extraction intact.
        assertThat(outcome.learned().getFirst().body())
                .contains("editing the assertion does not");
        assertThat(outcome.committed()).isTrue();
    }

    @Test
    void mostRoundsLearnNothingAndThatIsNotAnError()
    {
        assertThat(agent.read("COMMITTED: fixed it", 300, "s-1").learned()).isEmpty();
        // A malformed block is dropped rather than persisted half-written.
        assertThat(agent.read(
                "<learned title=\"\"></learned>\nCOMMITTED: fixed it", 300, "s-1").learned())
                .isEmpty();
    }

    @Test
    void aRetrospectiveMayWriteSeveralEntriesOrNone()
    {
        HarnessRepairAgent.Outcome outcome = agent.read("""
                <learned title="the fork keeps its own config prefix">
                Upstream renames keys; this fork does not follow. Rename in the fixup.
                </learned>
                <learned title="coverage gate needs a stub for new fork-only classes">
                Adding one to the generated list is enough; the gate reads it at build time.
                </learned>
                NOTHING: two things worth keeping from this range
                """, 900, "s-9");

        assertThat(outcome.learned()).hasSize(2);
        assertThat(outcome.learned().stream().map(HarnessRepairAgent.Learned::title))
                .contains("the fork keeps its own config prefix");
        // A retrospective is not a fix: nothing here should ever be pushed.
        assertThat(outcome.committed()).isFalse();
    }

    private static Failure failure(String signature)
    {
        return new Failure(
                signature, "cycle", "run", 7L, "build", "core", null, null,
                signature, signature, "build", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
    }
}
