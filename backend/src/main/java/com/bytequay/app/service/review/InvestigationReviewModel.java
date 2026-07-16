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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.InvestigationReviewRunner.RunOutcome;

import java.util.List;

/** Model-turn boundary used by the review orchestrator. */
interface InvestigationReviewModel
{
    ProviderChoice choose(String requestedRunner, String requestedProvider);

    ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner);

    RunOutcome investigate(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String coverageContext,
            String persona, int costCapCents);

    RunOutcome planGuidance(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String guidance, int costCapCents);

    RunOutcome verifyGuidance(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String guidance, int costCapCents);

    RunOutcome selfRefute(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String findingBundles,
            int costCapCents);

    RunOutcome reconstruct(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String locations,
            String persona, int costCapCents);

    RunOutcome verify(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
            String findingBundle, String blindReconstruction,
            String persona, int costCapCents);

    String suggestPlanAmendment(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives);
}
