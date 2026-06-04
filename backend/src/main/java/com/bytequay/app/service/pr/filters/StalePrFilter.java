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

import java.time.Duration;
import java.time.Instant;

/** Open PRs that haven't moved for a long time. */
@Component
@Concept(
        name = "stale",
        aka = "rotting",
        kind = ConceptKind.FILTER,
        definition = "An open, non-draft PR whose last update is more than seven days "
                + "ago and which has no recorded reviewer verdicts — the rows the "
                + "review queue has quietly forgotten about.",
        relatedTools = "list_prs",
        relatedConcepts = {"pr", "urgent"})
public class StalePrFilter
        implements NamedFilter<PullRequest>
{
    static final Duration STALE_THRESHOLD = Duration.ofDays(7);

    @Override
    public String name()
    {
        return "stale";
    }

    @Override
    public boolean matches(PullRequest pr, Instant now)
    {
        if (pr.mergedAt() != null || "closed".equalsIgnoreCase(pr.state()) || pr.draft()) {
            return false;
        }
        if (pr.reviewerVerdicts() != null && !pr.reviewerVerdicts().isEmpty()) {
            return false;
        }
        Instant updated = pr.updatedAt();
        if (updated == null) {
            return false;
        }
        return Duration.between(updated, now).compareTo(STALE_THRESHOLD) > 0;
    }
}
