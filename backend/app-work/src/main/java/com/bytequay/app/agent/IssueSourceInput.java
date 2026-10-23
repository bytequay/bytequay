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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The identity and revision of the issue shown in the task composer.
 *
 * @param replaces the issue's ended attempt that Start another attempt replaces; without it, the issue's current task opens
 */
public record IssueSourceInput(long issueId, int number, Instant updatedAt, Optional<String> replaces)
{
    public IssueSourceInput
    {
        checkArgument(issueId > 0 && number > 0, "invalid issue identity");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(replaces, "replaces is null");
    }
}
