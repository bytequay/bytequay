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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Commits the local review-to-build handoff after remote facts are resolved. */
@Component
public final class ReviewBuildSpawnCommitter
{
    private final ThreadService threads;
    private final ThreadStore threadStore;
    private final ReviewStore reviews;
    private final ReviewBuildSelectionStore selections;

    public ReviewBuildSpawnCommitter(
            ThreadService threads,
            ThreadStore threadStore,
            ReviewStore reviews,
            ReviewBuildSelectionStore selections)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.selections = requireNonNull(selections, "selections is null");
    }

    @Transactional
    public Thread commit(
            ThreadService.NewTaskRequest request,
            ReviewPass pass,
            List<ReviewFinding> selected,
            Instant frozenAt)
    {
        requireNonNull(request, "request is null");
        requireNonNull(pass, "pass is null");
        requireNonNull(selected, "selected is null");
        requireNonNull(frozenAt, "frozenAt is null");
        Thread created = threads.create(request);
        Thread linked = new Thread(
                created.id(), created.kind(), created.provider(),
                created.agentSessionId(), created.title(), created.status(),
                created.model(), created.costUsdMilli(), created.tokensIn(),
                created.tokensOut(), created.createdAt(), created.updatedAt(),
                created.endedAt(), created.errorMessage(), created.flow(),
                created.workspaceId(), created.workModel(), pass.id(),
                created.parallelSlots(), created.parentTaskId(), created.prRef(),
                created.description());
        threadStore.saveThread(linked);
        selections.freeze(
                linked.id(), pass.id(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(), selected, frozenAt);
        reviews.savePass(new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(), pass.phase(), pass.round(), pass.roundCap(),
                pass.costCapMilli(), pass.costUsdMilli(), pass.verdict(),
                pass.createdAt(), pass.endedAt(), linked.id(), pass.agendaJson(),
                pass.hostKind(), pass.hostId(), pass.kind(), pass.taskStageId()));
        return linked;
    }
}
