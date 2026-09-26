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
package com.bytequay.core.domain;

import java.net.URI;
import java.time.Instant;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The accepted Jira assignment, independent of later cache refreshes and the selected Git workspace. */
public record JiraIssueSource(String connectionId, String cloudId, String issueId, String key, String url, String title, String body, Instant updatedAt)
{
    public JiraIssueSource
    {
        checkText(connectionId, "connectionId");
        checkText(cloudId, "cloudId");
        checkText(issueId, "issueId");
        checkText(key, "key");
        checkText(title, "title");
        requireNonNull(body, "body is null");
        requireNonNull(updatedAt, "updatedAt is null");
        URI address = URI.create(requireNonNull(url, "url is null"));
        checkArgument("https".equalsIgnoreCase(address.getScheme()) && address.getHost() != null && address.getUserInfo() == null, "invalid Jira URL");
    }

    public String identity()
    {
        return cloudId.length() + ":" + cloudId + ":" + issueId;
    }
}
