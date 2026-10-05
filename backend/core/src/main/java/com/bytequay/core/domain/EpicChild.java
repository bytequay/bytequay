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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A child an Epic's source reports, a GitHub sub-issue or a Jira child ticket, as ByteQuay last read it.
 *
 * @param identity the canonical identity work items use for the child
 * @param status the source's own words for where the child stands, such as Open or In Progress
 * @param closed whether the source closed the child: a closed issue, or a ticket in a done status
 */
public record EpicChild(String identity, GoalItemKind kind, String key, String label, String title, String status, boolean closed, Optional<String> url)
{
    public EpicChild
    {
        checkText(identity, "identity");
        requireNonNull(kind, "kind is null");
        checkArgument(kind == GoalItemKind.ISSUE || kind == GoalItemKind.JIRA, "An Epic's child is an issue or a ticket");
        checkText(key, "key");
        checkText(label, "label");
        requireNonNull(title, "title is null");
        requireNonNull(status, "status is null");
        requireNonNull(url, "url is null");
    }

    /** The child as a work item's reference, which keeps its label and title once the child leaves every local cache. */
    public GoalItem reference(Instant at)
    {
        return new GoalItem(kind, key, label, title.isBlank() ? label : title, at);
    }
}
