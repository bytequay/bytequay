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

class GitHubEventMapper
{
    private GitHubEventMapper() {}

    static RecentEvent toRecentEvent(GitHubEventItem e)
    {
        GitHubEventItem.Payload payload = e.payload();
        int commitCount = payload != null && payload.size() != null ? payload.size() : 0;
        String action = payload != null ? payload.action() : null;
        String prTitle = payload != null && payload.pullRequest() != null ? payload.pullRequest().title() : null;
        int prNumber = payload != null && payload.pullRequest() != null ? payload.pullRequest().number() : 0;
        String refType = payload != null ? payload.refType() : null;
        String actorLogin = e.actor() != null ? e.actor().login() : null;
        return new RecentEvent(
                e.type(),
                e.repo() != null ? e.repo().name() : "",
                e.createdAt(),
                commitCount,
                action,
                prTitle,
                prNumber,
                refType,
                actorLogin);
    }
}
