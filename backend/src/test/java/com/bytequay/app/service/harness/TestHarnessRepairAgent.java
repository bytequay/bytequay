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

import com.bytequay.app.service.agents.AgentVerdictFile;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestHarnessRepairAgent
{
    private final HarnessRepairAgent agent = new HarnessRepairAgent(
            mock(CliReviewRunner.class),
            mock(WorkModelResolver.class),
            mock(SessionKnowledgeProvider.class),
            new ObjectMapper());

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
    void onlyACommittedVerdictIsPushed()
    {
        assertThat(outcome("committed", "fixed the compile break").committed()).isTrue();
        assertThat(outcome("nothing", "all cloud-gated").committed()).isFalse();
        assertThat(outcome("parked", "needs a human").committed()).isFalse();
    }

    @Test
    void nothingToDoIsDistinctFromBeingStuck()
    {
        assertThat(outcome("nothing", "all cloud-gated").nothing()).isTrue();
        assertThat(outcome("parked", "needs a human").nothing()).isFalse();
    }

    @Test
    void anUnknownStatusParksAndNamesWhatWasWritten()
    {
        // A status nobody handles is not a success. Parking is the only safe
        // reading, and naming it is how the prompt gets fixed.
        HarnessRepairAgent.Outcome outcome = outcome("finished", "all done!");

        assertThat(outcome.committed()).isFalse();
        assertThat(outcome.detail()).contains("unknown verdict status").contains("finished");
    }

    @Test
    void whatTheAgentLearnedIsLiftedOutForTheProgramToPersist()
    {
        List<HarnessRepairAgent.Learned> learned = HarnessRepairAgent.learned("""
                <learned title="stale plan fixtures after an optimizer change">
                Regenerating the fixtures fixes it; editing the assertion does not.
                </learned>
                """);

        assertThat(learned).hasSize(1);
        assertThat(learned.getFirst().title())
                .isEqualTo("stale plan fixtures after an optimizer change");
        assertThat(learned.getFirst().body()).contains("editing the assertion does not");
    }

    @Test
    void mostRoundsLearnNothingAndThatIsNotAnError()
    {
        assertThat(HarnessRepairAgent.learned("fixed it")).isEmpty();
        assertThat(HarnessRepairAgent.learned("<learned title=\"\"></learned>")).isEmpty();
    }

    private HarnessRepairAgent.Outcome outcome(String status, String summary)
    {
        return agent.outcomeOf(
                new AgentVerdictFile.Verdict(status, summary), List.of(), 300, "s-1");
    }

    private static Failure failure(String signature)
    {
        return new Failure(
                signature, "cycle", "run", 7L, "build", "core", null, null,
                signature, signature, "build", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
    }
}
