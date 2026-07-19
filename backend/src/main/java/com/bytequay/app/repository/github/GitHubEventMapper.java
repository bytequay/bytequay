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
package com.bytequay.app.repository.github;

import com.bytequay.app.domain.RecentEvent;

import java.util.List;

class GitHubEventMapper
{
    private GitHubEventMapper() {}

    static RecentEvent toRecentEvent(GitHubEventItem e)
    {
        GitHubEventItem.Payload payload = e.payload();
        int commitCount = payload != null && payload.size() != null ? payload.size() : 0;
        String action = payload != null ? payload.action() : null;
        GitHubEventItem.PrPayload pullRequest = payload != null ? payload.pullRequest() : null;
        GitHubEventItem.IssuePayload issue = payload != null ? payload.issue() : null;
        String prTitle = pullRequest != null ? pullRequest.title() : issue != null ? issue.title() : null;
        int prNumber = pullRequest != null ? pullRequest.number() : issue != null ? issue.number() : 0;
        String refType = payload != null ? payload.refType() : null;
        String actorLogin = e.actor() != null ? e.actor().login() : null;
        String actorAvatarUrl = e.actor() != null ? e.actor().avatarUrl() : null;
        String detail = payload == null ? null : eventDetail(payload.commits(), commitCount);
        String ref = payload != null ? payload.ref() : null;
        String reviewState = payload != null && payload.review() != null ? payload.review().state() : null;
        return new RecentEvent(
                e.type(),
                e.repo() != null ? e.repo().name() : "",
                e.createdAt(),
                commitCount,
                action,
                prTitle,
                prNumber,
                refType,
                actorLogin,
                actorAvatarUrl,
                detail,
                ref,
                reviewState);
    }

    private static String eventDetail(
            List<GitHubEventItem.CommitPayload> commits,
            int commitCount)
    {
        if (commits == null || commits.isEmpty()) {
            return null;
        }
        String first = commits.stream()
                .map(GitHubEventItem.CommitPayload::message)
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse(null);
        if (first == null) {
            return null;
        }
        String headline = first.lines().findFirst().orElse(first);
        int remaining = Math.max(commitCount, commits.size()) - 1;
        return remaining == 0 ? headline : headline + " · +" + remaining + " more";
    }
}
