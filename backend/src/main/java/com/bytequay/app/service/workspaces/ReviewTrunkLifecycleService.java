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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.stereotype.Service;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Keeps a PR's durable review trunk aligned with the GitHub lifecycle. */
@Service
public class ReviewTrunkLifecycleService
{
    private final ThreadStore threads;

    public ReviewTrunkLifecycleService(ThreadStore threads)
    {
        this.threads = requireNonNull(threads, "threads is null");
    }

    public void reconcile(String workspaceId, PullRequest pr)
    {
        String prRef = pr.repo() + "#" + pr.number();
        threads.findReviewTrunk(workspaceId, prRef)
                .ifPresent(thread -> reconcile(thread, isTerminal(pr)));
    }

    private void reconcile(Thread thread, boolean terminal)
    {
        if (terminal && thread.status() != ThreadStatus.ARCHIVED) {
            saveWithStatus(thread, ThreadStatus.ARCHIVED, Instant.now());
        }
        else if (!terminal && thread.status() == ThreadStatus.ARCHIVED) {
            saveWithStatus(thread, ThreadStatus.IDLE, null);
        }
    }

    private void saveWithStatus(
            Thread thread, ThreadStatus status, Instant endedAt)
    {
        threads.saveThread(new Thread(
                thread.id(),
                thread.kind(),
                thread.provider(),
                thread.agentSessionId(),
                thread.title(),
                status,
                thread.model(),
                thread.costUsdMilli(),
                thread.tokensIn(),
                thread.tokensOut(),
                thread.createdAt(),
                Instant.now(),
                endedAt,
                null,
                thread.flow(),
                thread.workspaceId(),
                thread.workModel(),
                thread.parentReviewPassId(),
                thread.parallelSlots(),
                thread.parentTaskId(),
                thread.prRef()));
    }

    private static boolean isTerminal(PullRequest pr)
    {
        return pr.mergedAt() != null
                || "merged".equalsIgnoreCase(pr.state())
                || "closed".equalsIgnoreCase(pr.state());
    }
}
