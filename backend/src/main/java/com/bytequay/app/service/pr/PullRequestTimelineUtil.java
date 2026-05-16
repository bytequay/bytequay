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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PrTimelineEvent;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

final class PullRequestTimelineUtil
{
    // Either form is allowed after a closing keyword:
    //   #1234                                    (group 2 = number)
    //   https://github.com/owner/repo/issues/N   (groups 3/4/5 = owner/repo/number)
    // The URL form is filtered down to same-repo refs in extractClosingReferences;
    // cross-repo URLs are skipped -- see Phase 2.5 GraphQL follow-up.
    private static final Pattern CLOSING_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+"
                    + "(?:#(\\d+)|https?://github\\.com/([^/\\s]+)/([^/\\s]+)/issues/(\\d+))");

    private PullRequestTimelineUtil() {}

    /**
     * Merges issue comments into the timeline, replacing sparse timeline
     * comment events with the richer issue-comment rows when both endpoints
     * report the same comment.
     */
    static List<PrTimelineEvent> mergeIssueComments(
            List<PrTimelineEvent> timeline,
            List<PrTimelineEvent> issueComments)
    {
        if (issueComments.isEmpty()) {
            return timeline;
        }
        Set<String> issueCommentKeys = issueComments.stream()
                .map(PullRequestTimelineUtil::commentKey)
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        List<PrTimelineEvent> out = Lists.newArrayList();
        for (PrTimelineEvent event : timeline) {
            if ("commented".equals(event.event()) && issueCommentKeys.contains(commentKey(event))) {
                continue;
            }
            out.add(event);
        }
        out.addAll(issueComments);
        return ImmutableList.copyOf(out);
    }

    /**
     * Pulls issue numbers from "closes #N" / "fixes #N" / "resolves #N"
     * style references in a PR body. URL references are kept only when they
     * point at the PR's own repository.
     */
    static Set<Integer> extractClosingReferences(String body, String prOwner, String prRepo)
    {
        if (body == null || body.isBlank()) {
            return ImmutableSet.of();
        }
        Set<Integer> out = Sets.newLinkedHashSet();
        Matcher matcher = CLOSING_KEYWORD_PATTERN.matcher(body);
        while (matcher.find()) {
            try {
                String hashNumber = matcher.group(2);
                if (hashNumber != null) {
                    out.add(Integer.parseInt(hashNumber));
                    continue;
                }
                String urlOwner = matcher.group(3);
                String urlRepo = matcher.group(4);
                String urlNumber = matcher.group(5);
                if (urlNumber != null && urlOwner != null && urlRepo != null
                        && urlOwner.equalsIgnoreCase(prOwner)
                        && urlRepo.equalsIgnoreCase(prRepo)) {
                    out.add(Integer.parseInt(urlNumber));
                }
            }
            catch (NumberFormatException ignored) {
                // Pattern guarantees digits, so this is unreachable in practice.
            }
        }
        return ImmutableSet.copyOf(out);
    }

    private static String commentKey(PrTimelineEvent event)
    {
        if (event == null || event.actor() == null || event.timestamp() == null) {
            return null;
        }
        return event.actor() + "@" + event.timestamp();
    }
}
