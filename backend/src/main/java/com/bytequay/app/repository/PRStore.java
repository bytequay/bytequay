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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the {@link PR} aggregate — the PR row plus
 * its commits, timeline events, checks, and comments. One task has at most
 * one local PR ({@link #findByTaskId}).
 */
public interface PRStore
{
    // ── pr ─────────────────────────────────────────────────────────
    /** Insert or update the PR row; returns the persisted value. */
    PR save(PR pr);

    Optional<PR> findById(String id);

    Optional<PR> findByTaskId(String taskId);

    /** The external PR already synced in for this (repo, remote PR number),
     *  if any — the dashboard/details-page resolver's idempotency check. */
    Optional<PR> findByRepoAndRemotePrNumber(String repo, int remotePrNumber);

    // ── pr_commit ──────────────────────────────────────────────────
    PRCommit addCommit(PRCommit commit);

    List<PRCommit> commitsFor(String prId);

    // ── pr_timeline_event ──────────────────────────────────────────
    PRTimelineEntry addEvent(PRTimelineEntry event);

    List<PRTimelineEntry> timelineFor(String prId);

    /** Local-only events not yet stripped — the push transition stamps these. */
    List<PRTimelineEntry> unstrippedLocalOnlyEvents(String prId);

    /** Whether a remote-synced event with this GitHub id has already been
     *  mirrored onto the timeline — keeps a repeated remote-timeline sync
     *  idempotent. */
    boolean timelineEventExistsByRemoteId(String prId, long remoteEventId);

    // ── pr_check ───────────────────────────────────────────────────
    PRCheck addCheck(PRCheck check);

    List<PRCheck> checksFor(String prId);

    // ── pr_comment ─────────────────────────────────────────────────
    /** Insert or update a comment (add, resolve, edit, or stamp stripped). */
    PRComment saveComment(PRComment comment);

    Optional<PRComment> findCommentById(String id);

    List<PRComment> commentsFor(String prId);

    /** Local-origin comments not yet stripped — the push transition stamps these. */
    List<PRComment> unstrippedLocalComments(String prId);
}
