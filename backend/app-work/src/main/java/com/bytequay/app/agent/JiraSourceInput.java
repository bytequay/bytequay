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
package com.bytequay.app.agent;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * Renderer-accepted source fields are compared with local and authoritative Jira data, never used as authority.
 *
 * @param replaces the ticket's ended attempt that Start another attempt replaces; without it, the ticket's current task opens
 */
public record JiraSourceInput(String connectionId, String cloudId, String issueId, String key, String title, String body, Instant updatedAt, Optional<String> replaces)
{
    public JiraSourceInput
    {
        checkText(connectionId, "connectionId");
        checkText(cloudId, "cloudId");
        checkText(issueId, "issueId");
        checkText(key, "key");
        checkText(title, "title");
        requireNonNull(body, "body is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(replaces, "replaces is null");
    }
}
