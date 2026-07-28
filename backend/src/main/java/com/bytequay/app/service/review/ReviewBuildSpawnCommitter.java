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
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Commits one exact local review-to-build handoff after remote facts resolve. */
@Component
public final class ReviewBuildSpawnCommitter
{
    private final ThreadService threads;
    private final ThreadStore threadStore;
    private final ReviewBuildSelectionStore selections;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ReviewBuildSpawnCommitter(
            ThreadService threads,
            ThreadStore threadStore,
            ReviewBuildSelectionStore selections,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.selections = requireNonNull(selections, "selections is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(requireNonNull(
                transactionManager, "transactionManager is null"));
    }

    /** Thread creation, immutable freeze, and pass attachment share one transaction. */
    public CommittedSpawn commit(
            ThreadService.NewTaskRequest request,
            ReviewPass pass,
            ReviewBuildSelectionStore.SpawnInput spawn,
            List<ReviewFinding> selected,
            Instant frozenAt)
    {
        requireNonNull(request, "request is null");
        requireNonNull(pass, "pass is null");
        requireNonNull(spawn, "spawn is null");
        requireNonNull(selected, "selected is null");
        requireNonNull(frozenAt, "frozenAt is null");

        return requireNonNull(transactions.execute(ignored -> commitInTransaction(
                request, pass, spawn, selected, frozenAt)),
                "review build transaction returned no result");
    }

    private CommittedSpawn commitInTransaction(
            ThreadService.NewTaskRequest request,
            ReviewPass pass,
            ReviewBuildSelectionStore.SpawnInput spawn,
            List<ReviewFinding> selected,
            Instant frozenAt)
    {
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
        Optional<ReviewBuildSelectionStore.Selection> selection = isV2Trunk(linked.id())
                ? Optional.of(selections.freeze(
                        linked.id(), pass.id(), pass.repoFullName(), pass.prNumber(),
                        pass.headSha(), spawn, selected, frozenAt))
                : Optional.empty();

        int attached = jdbc.update("""
                UPDATE review_passes
                SET spawned_build_thread_id = ?
                WHERE id = ? AND thread_id = ? AND repo_full_name = ?
                  AND pr_number = ? AND head_sha = ? AND phase = 'TERMINATE'
                  AND round = ? AND spawned_build_thread_id IS NULL
                """,
                linked.id(), pass.id(), pass.threadId(), pass.repoFullName(),
                pass.prNumber(), pass.headSha(), pass.round());
        if (attached != 1) {
            throw new SpawnAttachConflict(pass.id());
        }
        return new CommittedSpawn(linked, selection);
    }

    public Optional<CommittedSpawn> findCommitted(String reviewPassId)
    {
        List<String> attached = jdbc.query("""
                SELECT spawned_build_thread_id FROM review_passes WHERE id = ?
                """, (rs, row) -> rs.getString("spawned_build_thread_id"),
                reviewPassId);
        if (attached.size() != 1 || attached.getFirst() == null) {
            return Optional.empty();
        }
        String threadId = attached.getFirst();
        Thread thread = threadStore.findThreadById(threadId)
                .orElseThrow(() -> new IllegalStateException(
                        "review build attachment names a missing Trunk"));
        Optional<ReviewBuildSelectionStore.Selection> selection = selections
                .findByReviewPass(reviewPassId);
        if (!reviewPassId.equals(thread.parentReviewPassId())
                || (selection.isPresent()
                    && !threadId.equals(selection.orElseThrow().threadId()))
                || isV2Trunk(threadId) != selection.isPresent()) {
            throw new IllegalStateException(
                    "review build pass, selection, and Trunk links disagree");
        }
        return Optional.of(new CommittedSpawn(thread, selection));
    }

    private boolean isV2Trunk(String threadId)
    {
        String turnVersion = jdbc.queryForObject("""
                SELECT turn_version FROM threads WHERE id = ?
                """, String.class, threadId);
        return "V2".equals(turnVersion);
    }

    public record CommittedSpawn(
            Thread thread,
            Optional<ReviewBuildSelectionStore.Selection> selection)
    {
        public CommittedSpawn
        {
            requireNonNull(thread, "thread is null");
            requireNonNull(selection, "selection is null");
            if (selection.isPresent()
                    && !thread.id().equals(selection.orElseThrow().threadId())) {
                throw new IllegalArgumentException(
                        "committed review build spans two Trunks");
            }
        }
    }

    public static final class SpawnAttachConflict
            extends IllegalStateException
    {
        SpawnAttachConflict(String passId)
        {
            super("review pass changed before build Trunk attachment: " + passId);
        }
    }
}
