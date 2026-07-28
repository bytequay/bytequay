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
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.local.GitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Legacy review→build compatibility: when a spawned build thread's work ships
 * — its task's commits land, or its suggested-change comments are
 * accepted — the {@code #finding-<id>} refs in those commit subjects /
 * comment bodies flip the matching AGREED findings on the parent review
 * pass to RESOLVED. V2 review-build Trunks are excluded: only their exact
 * completed TaskOutcome may resolve the frozen selection.
 *
 * <p>Idempotent (a finding already past AGREED is skipped) and scoped:
 * a {@code #finding-<id>} that belongs to a different pass is ignored,
 * not an error. The pass row itself is never closed here — it stays
 * TERMINATE until every AGREED finding is resolved or dropped.
 */
@Service
public class ReviewPassResolver
{
    private static final Logger log = LoggerFactory.getLogger(ReviewPassResolver.class);
    private static final int COMMIT_SCAN_LIMIT = 200;

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final ReviewStore reviewStore;
    private final GitRunner git;
    private final ReviewBuildSelectionStore selections;

    public ReviewPassResolver(
            ThreadStore threadStore,
            TaskStore taskStore,
            ReviewStore reviewStore,
            GitRunner git,
            ReviewBuildSelectionStore selections)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.git = requireNonNull(git, "git is null");
        this.selections = requireNonNull(selections, "selections is null");
    }

    /**
     * Best-effort hook fired after a build thread successfully publishes
     * (a suggested-change comment, or a push/ship of its task). Scans the
     * posted body plus the thread's commit subjects for {@code
     * #finding-<id>} refs and resolves the matching AGREED findings on the
     * parent pass. Never throws into the publish path.
     */
    public int onPublishApproved(String threadId, String action, String editedBody)
    {
        try {
            Thread thread = threadStore.findThreadById(threadId).orElse(null);
            if (thread == null || thread.parentReviewPassId() == null) {
                return 0;
            }
            if (selections.find(threadId).isPresent()) {
                return 0;
            }
            List<String> texts = new ArrayList<>();
            if (editedBody != null && !editedBody.isBlank()) {
                texts.add(editedBody);
            }
            texts.addAll(commitSubjects(thread));
            return resolveOnPass(thread.parentReviewPassId(), threadId, texts);
        }
        catch (RuntimeException e) {
            log.warn("Review-pass resolve after publish of thread {} ({}) failed: {}",
                    threadId, action, e.getMessage());
            return 0;
        }
    }

    /** Flip parent-pass AGREED findings referenced via {@code #finding-id}
     *  in the given texts to RESOLVED. Returns the number flipped. */
    public int resolveFromTexts(String buildThreadId, Collection<String> texts)
    {
        Thread thread = threadStore.findThreadById(buildThreadId).orElse(null);
        if (thread == null || thread.parentReviewPassId() == null) {
            return 0;
        }
        if (selections.find(buildThreadId).isPresent()) {
            return 0;
        }
        return resolveOnPass(thread.parentReviewPassId(), buildThreadId, texts);
    }

    int resolveOnPass(String passId, String buildThreadId, Collection<String> texts)
    {
        Set<String> referenced = new LinkedHashSet<>();
        for (String text : texts) {
            for (MentionRefParser.Ref ref : MentionRefParser.parse(text == null ? "" : text).refs()) {
                if ("finding".equals(ref.kind())) {
                    referenced.add(ref.targetId());
                }
            }
        }
        if (referenced.isEmpty()) {
            return 0;
        }
        Map<String, ReviewFinding> byId = new HashMap<>();
        for (ReviewFinding f : reviewStore.listFindingsForPass(passId)) {
            byId.put(f.id(), f);
        }
        int flipped = 0;
        for (String id : referenced) {
            ReviewFinding f = byId.get(id);
            if (f == null) {
                log.debug("#finding-{} is not on pass {} — ignoring", id, passId);
                continue;
            }
            if (f.status() != ReviewFindingStatus.AGREED) {
                continue; // idempotent: only AGREED → RESOLVED
            }
            reviewStore.saveFinding(resolved(f, buildThreadId));
            flipped++;
        }
        if (flipped > 0) {
            log.info("Resolved {} AGREED finding(s) on review pass {} from build thread {}",
                    flipped, passId, buildThreadId);
        }
        return flipped;
    }

    private List<String> commitSubjects(Thread thread)
    {
        // The build thread's newest active task (else its latest) carries the
        // worktree whose commit subjects we scan for #finding-<id> refs.
        Task task = taskStore.activeTasksForThread(thread.id()).stream().findFirst()
                .or(() -> taskStore.findLatestTaskForThread(thread.id()))
                .orElse(null);
        if (task == null || task.worktreePath() == null || task.branchName() == null) {
            return List.of();
        }
        try {
            return git.listCommits(Path.of(task.worktreePath()), task.branchName(), COMMIT_SCAN_LIMIT)
                    .stream()
                    .map(GitRunner.CommitEntry::subject)
                    .toList();
        }
        catch (Exception e) {
            log.warn("Commit scan for thread {} failed: {}", thread.id(), e.getMessage());
            return List.of();
        }
    }

    private static ReviewFinding resolved(ReviewFinding f, String buildThreadId)
    {
        return new ReviewFinding(
                f.id(), f.reviewPassId(), f.path(), f.line(), f.severity(),
                ReviewFindingStatus.RESOLVED, f.body(), "build_thread_" + buildThreadId,
                f.postedCommentId(), f.createdAt(), f.debateStatus(), f.debateRounds());
    }
}
