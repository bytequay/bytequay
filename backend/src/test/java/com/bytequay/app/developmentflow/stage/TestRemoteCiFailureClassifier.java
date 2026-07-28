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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Check;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Evaluation;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.Policy;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy.PolicyOutcome;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestRemoteCiFailureClassifier
{
    private final RemoteCiFailureClassifier classifier =
            new RemoteCiFailureClassifier();

    @Test
    void requiresExplicitProvenanceBeforeStartingCodeRepair()
    {
        assertThat(classifier.classify(candidate(
                CheckState.FAILED, "failure-origin: task")))
                .isEqualTo(Classification.TASK_DETERMINISTIC);
        assertThat(classifier.classify(candidate(
                CheckState.FAILED, "failure-class: base-deterministic")))
                .isEqualTo(Classification.BASE_DETERMINISTIC);
        assertThat(classifier.classify(candidate(
                CheckState.FAILED, "ordinary compiler failure")))
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void separatesKnownFlakesAndInfrastructureFailures()
    {
        assertThat(classifier.classify(candidate(
                CheckState.FAILED, "known-flake: integration suite")))
                .isEqualTo(Classification.FLAKY);
        assertThat(classifier.classify(candidate(
                CheckState.CANCELED, "runner lost")))
                .isEqualTo(Classification.INFRASTRUCTURE);
        assertThat(classifier.classify(candidate(
                CheckState.FAILED, "service outage")))
                .isEqualTo(Classification.INFRASTRUCTURE);
    }

    private static Candidate candidate(CheckState state, String rawEvidence)
    {
        Check check = new Check(
                "CHECK_RUN", "check-1", "build", state,
                "completed", state.name(), null, 10L, rawEvidence);
        ObservationDelivery context = new ObservationDelivery(
                "row-1", "operation-1", "task-1", 1,
                "stage-1", 1, "binding-1", "policy-1",
                "acme/widget", 41, "head-1", "base-1",
                "head-1", "base-1", 0, 1, true,
                policy(), Set.of());
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        1, "observation-1", "head-1", "base-1",
                        RemoteObservationOperationHandler.PrState.OPEN,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        0, 0, 0, 0, 0, 0, List.of(check), rawEvidence, 10);
        Evaluation evaluation = new Evaluation(
                state, PolicyOutcome.FAILED, List.of(check), 1, 0);
        ObservationEvidence evidence = new ObservationEvidence(
                "snapshot-1", "evaluation-1", 1,
                "head-1", "base-1", PolicyOutcome.FAILED, 10);
        return new Candidate(context, observation, evaluation, evidence);
    }

    private static Policy policy()
    {
        return new Policy(Map.of(
                CheckState.NONE, PolicyOutcome.WAITING,
                CheckState.MISSING, PolicyOutcome.FAILED,
                CheckState.QUEUED, PolicyOutcome.WAITING,
                CheckState.PENDING, PolicyOutcome.WAITING,
                CheckState.NEUTRAL, PolicyOutcome.ACCEPTED,
                CheckState.SKIPPED, PolicyOutcome.ACCEPTED,
                CheckState.CANCELED, PolicyOutcome.FAILED));
    }
}
