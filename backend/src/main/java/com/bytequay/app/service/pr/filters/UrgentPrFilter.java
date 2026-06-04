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

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The worked example for the concept axis: a single named filter
 * that powers both the agent's {@code list_prs(filter:"urgent")}
 * call and any UI surface labelled "Urgent" — one definition, no
 * parallel logic to keep in sync.
 *
 * <p>The predicate is the union of the focus-band tiers that the
 * dashboard already considers "needs touching first":
 * <ul>
 *   <li>An auto-wake fired on a previously snoozed PR
 *       (<em>just-woke</em>).</li>
 *   <li>An authored PR that's ready to merge (an approval, no
 *       changes-requested, CI green, mergeable).</li>
 *   <li>An authored PR with a changes-requested verdict.</li>
 *   <li>CI failing on the head ref.</li>
 *   <li>A merge conflict on the head ref.</li>
 *   <li>Stale: no reviewer verdicts and no activity for more than
 *       seven days.</li>
 * </ul>
 *
 * <p>Merged / closed / draft / handled / snoozed PRs are filtered
 * out up-front so they don't surface even if a tier matches.
 */
@Component
@Concept(
        name = "urgent",
        aka = "needs-attention-now",
        kind = ConceptKind.FILTER,
        definition = "A PR that should be looked at first: ready-to-merge or stuck "
                + "(CI failing, merge conflict, changes requested, just-woken from "
                + "snooze, or no activity for more than a week).",
        examples = "urgent matches a PR whose CI just flipped to FAILING, even if "
                + "nothing else has changed.",
        relatedTools = "list_prs",
        relatedConcepts = {"pr", "stale", "blocked"})
public class UrgentPrFilter
        implements NamedFilter<PullRequest>
{
    /** Phase-band threshold for "stale, nobody reviewed it" — seven
     *  days matches the dashboard's existing focus band. */
    static final Duration STALE_THRESHOLD = Duration.ofDays(7);

    @Override
    public String name()
    {
        return "urgent";
    }

    @Override
    public boolean matches(PullRequest pr, Instant now)
    {
        if (!eligible(pr, now)) {
            return false;
        }
        return justWoke(pr)
                || readyToMerge(pr)
                || changesRequested(pr)
                || ciFailing(pr)
                || mergeConflict(pr)
                || stale(pr, now);
    }

    private static boolean eligible(PullRequest pr, Instant now)
    {
        if (pr.mergedAt() != null) {
            return false;
        }
        if ("closed".equalsIgnoreCase(pr.state())) {
            return false;
        }
        if (pr.draft()) {
            return false;
        }
        HandledAction handled = pr.handledAction();
        if (handled == HandledAction.MERGED
                || handled == HandledAction.DISMISSED
                || handled == HandledAction.MANUAL) {
            return false;
        }
        Instant snoozedUntil = pr.snoozedUntil();
        // A snoozed PR with no wake reason is parked and ineligible;
        // the wake-reason check below picks up the just-woke case.
        return snoozedUntil == null || !snoozedUntil.isAfter(now)
                || pr.snoozeWakeReason() != null;
    }

    private static boolean justWoke(PullRequest pr)
    {
        return pr.snoozeWakeReason() != null && !pr.snoozeWakeReason().isBlank();
    }

    private static boolean readyToMerge(PullRequest pr)
    {
        if (pr.origin() != PullRequest.Origin.AUTHORED) {
            return false;
        }
        Map<String, String> verdicts = pr.reviewerVerdicts();
        if (verdicts == null || !verdicts.containsValue("APPROVED")) {
            return false;
        }
        if (verdicts.containsValue("CHANGES_REQUESTED")) {
            return false;
        }
        return pr.ciStatus() == PullRequestDetail.CiStatus.PASSING
                && !Boolean.FALSE.equals(pr.mergeable());
    }

    private static boolean changesRequested(PullRequest pr)
    {
        return pr.origin() == PullRequest.Origin.AUTHORED
                && pr.reviewerVerdicts() != null
                && pr.reviewerVerdicts().containsValue("CHANGES_REQUESTED");
    }

    private static boolean ciFailing(PullRequest pr)
    {
        return pr.attentionReason() == AttentionReason.CI_FAILING
                || pr.ciStatus() == PullRequestDetail.CiStatus.FAILING;
    }

    private static boolean mergeConflict(PullRequest pr)
    {
        return pr.attentionReason() == AttentionReason.MERGE_CONFLICT
                || Boolean.FALSE.equals(pr.mergeable());
    }

    private static boolean stale(PullRequest pr, Instant now)
    {
        Instant updated = pr.updatedAt();
        if (updated == null) {
            return false;
        }
        if (pr.reviewerVerdicts() != null && !pr.reviewerVerdicts().isEmpty()) {
            return false;
        }
        return Duration.between(updated, now).compareTo(STALE_THRESHOLD) > 0;
    }
}
