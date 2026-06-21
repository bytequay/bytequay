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
package com.bytequay.app.service.pr.filters;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Open PRs whose review the system thinks I owe — the inbox-zero
 *  half of the dashboard.
 *
 *  <p>"Awaiting me" means: an open PR where the {@code origin} marks
 *  me as a requested reviewer and I haven't recorded a verdict yet.
 *  The reviewer-verdict side is conservative — if any verdict exists
 *  from anyone we treat the row as not-awaiting; the dashboard's
 *  finer per-login bookkeeping replaces this once the user signs in. */
@Component
@Concept(
        name = "awaiting_me",
        aka = {"to-review", "your-turn"},
        kind = ConceptKind.FILTER,
        definition = "An open PR that's been routed to me for review and where I "
                + "haven't recorded a verdict yet.",
        relatedTools = "list_prs",
        relatedConcepts = "pr")
public class AwaitingMePrFilter
        implements NamedFilter<PullRequest>
{
    @Override
    public String name()
    {
        return "awaiting_me";
    }

    @Override
    public boolean matches(PullRequest pr, Instant now)
    {
        if (pr.origin() != PullRequest.Origin.REVIEW_REQUESTED) {
            return false;
        }
        if (!PullRequestFilters.isOpen(pr) || pr.draft()) {
            return false;
        }
        return PullRequestFilters.hasNoReviewerVerdicts(pr);
    }
}
