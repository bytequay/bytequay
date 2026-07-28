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
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Conservative production classification: code edits require explicit provenance. */
@Component
public final class RemoteCiFailureClassifier
        implements RemoteCiRepairRuntimeCoordinator.FailureClassifier
{
    @Override
    public Classification classify(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        String evidence = canonical(candidate.observation().rawEvidence());
        for (Check check : candidate.ciEvaluation().checks()) {
            evidence += canonical(check.kind())
                    + canonical(check.name())
                    + canonical(check.providerStatus())
                    + canonical(check.providerConclusion())
                    + canonical(check.rawEvidence());
        }

        if (evidence.contains("FAILUREORIGINBASE")
                || evidence.contains("FAILURECLASSBASEDETERMINISTIC")) {
            return Classification.BASE_DETERMINISTIC;
        }
        if (evidence.contains("FAILUREORIGINTASK")
                || evidence.contains("FAILURECLASSTASKDETERMINISTIC")) {
            return Classification.TASK_DETERMINISTIC;
        }
        if (evidence.contains("FAILURECLASSFLAKY")
                || evidence.contains("KNOWNFLAKE")) {
            return Classification.FLAKY;
        }
        if (candidate.ciEvaluation().checks().stream().anyMatch(
                RemoteCiFailureClassifier::infrastructureFailure)) {
            return Classification.INFRASTRUCTURE;
        }
        return Classification.UNKNOWN;
    }

    private static boolean infrastructureFailure(Check check)
    {
        if (check.state() == CheckState.CANCELED) {
            return true;
        }
        String evidence = canonical(check.providerStatus())
                + canonical(check.providerConclusion())
                + canonical(check.rawEvidence());
        return evidence.contains("TIMEDOUT")
                || evidence.contains("STARTUPFAILURE")
                || evidence.contains("ACTIONREQUIRED")
                || evidence.contains("RUNNERUNAVAILABLE")
                || evidence.contains("RUNNERLOST")
                || evidence.contains("SERVICEOUTAGE")
                || evidence.contains("INFRASTRUCTUREFAILURE");
    }

    private static String canonical(String value)
    {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ENGLISH)
                .replaceAll("[^A-Z0-9]", "");
    }
}
