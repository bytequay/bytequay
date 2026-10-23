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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestTimelineService
{
    private final PrDetailStore store;

    public PullRequestTimelineService(PrDetailStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Records one idempotent local event after a push has been reconciled.
     * SHAs must come from the durable push journal, never from agent prose, so
     * they are rechecked here. The pushed commits already appear in the GitHub
     * timeline and the changed files in the Files tab, so the event names only
     * the push.
     */
    public void record(PullRequestRef pullRequest, String eventKey, String actor, Instant timestamp, String expectedRemoteSha, String pushedSha)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        checkText(eventKey, "eventKey", 200);
        checkText(actor, "actor", 100);
        requireNonNull(timestamp, "timestamp is null");
        checkSha(expectedRemoteSha, "expectedRemoteSha");
        checkSha(pushedSha, "pushedSha");
        String body = "CI Autofix pushed " + pushedSha.substring(0, 7) + " over " + expectedRemoteSha.substring(0, 7);
        store.recordLocalTimelineEvent(pullRequest, new PrTimelineEvent(null, "ci_autofix", actor, timestamp, body, Optional.of(eventKey)));
    }
}
