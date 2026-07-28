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

import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;

import static java.util.Objects.requireNonNull;

/** Synchronous Remote Stage owner boundary for folding one immutable snapshot. */
@FunctionalInterface
public interface RemoteObservationConsumer
{
    Consumption consume(Candidate candidate, SubjectAcceptance acceptance);

    enum Consumption
    {
        ACCEPTED,
        SUPERSEDED
    }

    @FunctionalInterface
    interface SubjectAcceptance
    {
        /** Advances the exact Remote code subject inside the current Task command. */
        void accept();
    }

    record Candidate(
            ObservationDelivery context,
            RemoteObservationOperationHandler.Observation observation,
            RemoteCiPolicy.Evaluation ciEvaluation,
            ObservationEvidence evidence)
    {
        public Candidate
        {
            requireNonNull(context, "context is null");
            requireNonNull(observation, "observation is null");
            requireNonNull(ciEvaluation, "ciEvaluation is null");
            requireNonNull(evidence, "evidence is null");
        }
    }
}
