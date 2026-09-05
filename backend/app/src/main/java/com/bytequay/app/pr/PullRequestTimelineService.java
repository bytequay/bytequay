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

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestTimelineService
{
    private static final int MAXIMUM_BODY_CHARACTERS = 16 * 1024;
    private static final int MAXIMUM_VALUES = 1000;

    private final PrDetailStore store;

    public PullRequestTimelineService(PrDetailStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Records one idempotent local event after a push has been reconciled.
     * Subjects, paths, and SHAs must come from verified Git and the durable push
     * journal, never from agent prose, so every value is rechecked here.
     */
    public void record(
            PullRequestRef pullRequest,
            String eventKey,
            String actor,
            Instant timestamp,
            String expectedRemoteSha,
            String pushedSha,
            List<String> commitSubjects,
            List<String> changedPaths)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        checkText(eventKey, "eventKey", 200);
        checkText(actor, "actor", 100);
        requireNonNull(timestamp, "timestamp is null");
        checkSha(expectedRemoteSha, "expectedRemoteSha");
        checkSha(pushedSha, "pushedSha");
        String body = body(
                expectedRemoteSha,
                pushedSha,
                checkBounded(commitSubjects, "commitSubjects"),
                checkBounded(changedPaths, "changedPaths"));
        store.recordLocalTimelineEvent(
                pullRequest,
                new PrTimelineEvent(null, "ci_autofix", actor, timestamp, body, Optional.of(eventKey)));
    }

    private static String body(
            String expectedRemoteSha,
            String pushedSha,
            List<String> commitSubjects,
            List<String> changedPaths)
    {
        StringBuilder body = new StringBuilder("CI Autofix pushed `")
                .append(pushedSha)
                .append("` over `")
                .append(expectedRemoteSha)
                .append("`.");
        appendList(body, "Commits", commitSubjects);
        appendList(body, "Changed paths", changedPaths);
        if (body.length() <= MAXIMUM_BODY_CHARACTERS) {
            return body.toString();
        }
        return body.substring(0, MAXIMUM_BODY_CHARACTERS - 1) + "…";
    }

    private static void appendList(StringBuilder body, String title, List<String> values)
    {
        body.append("\n\n").append(title).append(':');
        for (String value : values) {
            body.append("\n- ").append(value.replace('\r', ' ').replace('\n', ' '));
        }
    }

    private static List<String> checkBounded(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        List<String> copy = ImmutableList.copyOf(values);
        checkArgument(copy.size() <= MAXIMUM_VALUES, name + " exceeds " + MAXIMUM_VALUES + " values");
        copy.forEach(value -> checkText(value, name, 500));
        return copy;
    }

    private static void checkSha(String value, String name)
    {
        checkText(value, name, 64);
        checkArgument(value.matches("[0-9a-fA-F]{40,64}"), name + " is not a Git SHA");
    }
}
