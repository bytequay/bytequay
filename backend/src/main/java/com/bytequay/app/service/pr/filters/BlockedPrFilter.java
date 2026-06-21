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

import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Authored PRs whose author has work to do — a reviewer asked for
 *  changes. */
@Component
@Concept(
        name = "blocked",
        aka = {"changes-requested", "needs-changes"},
        kind = ConceptKind.FILTER,
        definition = "A PR I authored where at least one reviewer has recorded a "
                + "CHANGES_REQUESTED verdict — the author is the one who needs to "
                + "act, not the reviewer.",
        relatedTools = "list_prs",
        relatedConcepts = {"pr", "urgent"})
public class BlockedPrFilter
        implements NamedFilter<PullRequest>
{
    @Override
    public String name()
    {
        return "blocked";
    }

    @Override
    public boolean matches(PullRequest pr, Instant now)
    {
        if (pr.origin() != PullRequest.Origin.AUTHORED) {
            return false;
        }
        if (!PullRequestFilters.isOpen(pr)) {
            return false;
        }
        return PullRequestFilters.hasReviewerVerdict(pr, GithubReviewState.CHANGES_REQUESTED);
    }
}
