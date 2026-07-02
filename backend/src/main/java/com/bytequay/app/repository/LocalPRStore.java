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
package com.bytequay.app.repository;

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRCommit;
import com.bytequay.app.domain.LocalPRTimelineEvent;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the {@link LocalPR} aggregate — the PR row plus
 * its commits, timeline events, checks, and comments. One task has at most
 * one local PR ({@link #findByTaskId}).
 */
public interface LocalPRStore
{
    // ── local_pr ─────────────────────────────────────────────────────────
    /** Insert or update the PR row; returns the persisted value. */
    LocalPR save(LocalPR pr);

    Optional<LocalPR> findById(String id);

    Optional<LocalPR> findByTaskId(String taskId);

    // ── local_pr_commit ──────────────────────────────────────────────────
    LocalPRCommit addCommit(LocalPRCommit commit);

    List<LocalPRCommit> commitsFor(String localPrId);

    // ── local_pr_timeline_event ──────────────────────────────────────────
    LocalPRTimelineEvent addEvent(LocalPRTimelineEvent event);

    List<LocalPRTimelineEvent> timelineFor(String localPrId);

    /** Local-only events not yet stripped — the push transition stamps these. */
    List<LocalPRTimelineEvent> unstrippedLocalOnlyEvents(String localPrId);

    // ── local_pr_check ───────────────────────────────────────────────────
    LocalPRCheck addCheck(LocalPRCheck check);

    List<LocalPRCheck> checksFor(String localPrId);

    // ── local_pr_comment ─────────────────────────────────────────────────
    /** Insert or update a comment (add, resolve, edit, or stamp stripped). */
    LocalPRComment saveComment(LocalPRComment comment);

    Optional<LocalPRComment> findCommentById(String id);

    List<LocalPRComment> commentsFor(String localPrId);

    /** Local-origin comments not yet stripped — the push transition stamps these. */
    List<LocalPRComment> unstrippedLocalComments(String localPrId);
}
