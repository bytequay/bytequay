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
package com.bytequay.app.pr;

import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Verified local facts for one CI Autofix push shown on the pull-request timeline. */
public record RecordPrTimelineEventCommand(
        PullRequestRef pullRequest,
        String eventKey,
        String actor,
        Instant timestamp,
        String expectedRemoteSha,
        String pushedSha,
        List<String> commitSubjects,
        List<String> changedPaths)
{
    public RecordPrTimelineEventCommand
    {
        requireNonNull(pullRequest, "pullRequest is null");
        checkText(eventKey, "eventKey", 200);
        checkText(actor, "actor", 100);
        requireNonNull(timestamp, "timestamp is null");
        checkSha(expectedRemoteSha, "expectedRemoteSha");
        checkSha(pushedSha, "pushedSha");
        commitSubjects = copyBounded(commitSubjects, "commitSubjects");
        changedPaths = copyBounded(changedPaths, "changedPaths");
    }

    private static List<String> copyBounded(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        List<String> copy = ImmutableList.copyOf(values);
        checkArgument(copy.size() <= 1000, name + " exceeds 1000 values");
        copy.forEach(value -> checkText(value, name, 500));
        return copy;
    }

    private static void checkSha(String value, String name)
    {
        checkText(value, name, 64);
        checkArgument(value.matches("[0-9a-fA-F]{40,64}"), name + " is not a Git SHA");
    }

    private static void checkText(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), name + " is blank");
        checkArgument(value.length() <= maximumLength, name + " exceeds " + maximumLength + " characters");
    }
}
