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
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Immutable, revision-fenced review selection consumed by V2 Task creation. */
@Component
public class ReviewBuildSelectionStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReviewBuildSelectionStore(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Transactional
    public Selection freeze(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
            SpawnInput spawn,
            List<ReviewFinding> findings,
            Instant frozenAt)
    {
        requireText(threadId, "threadId");
        requireText(reviewPassId, "reviewPassId");
        requireText(repoFullName, "repoFullName");
        requireText(reviewedHeadSha, "reviewedHeadSha");
        requireNonNull(spawn, "spawn is null");
        requireNonNull(findings, "findings is null");
        requireNonNull(frozenAt, "frozenAt is null");
        if (prNumber < 1 || findings.isEmpty()) {
            throw new IllegalArgumentException(
                    "review build requires a PR and at least one finding");
        }

        List<Finding> frozen = new ArrayList<>(findings.size());
        Set<String> ids = new HashSet<>();
        for (ReviewFinding expected : findings) {
            requireNonNull(expected, "finding is null");
            if (!ids.add(expected.id())) {
                throw new IllegalArgumentException(
                        "review finding selection contains a duplicate id");
            }
            CurrentFinding current = findCurrent(expected.id()).orElseThrow(() ->
                    new IllegalStateException(
                            "selected review finding no longer exists: " + expected.id()));
            if (!expected.equals(current.finding())) {
                throw new IllegalStateException(
                        "selected review finding changed before freeze: " + expected.id());
            }
            requireEligible(reviewPassId, current.finding());
            String content = snapshot(current.finding());
            frozen.add(new Finding(
                    reviewPassId, current.finding().id(), current.revision(),
                    content, digest(content)));
        }

        Selection candidate = selection(
                threadId, reviewPassId, repoFullName, prNumber,
                reviewedHeadSha, spawn, frozen, frozenAt);
        int inserted = jdbc.update("""
                    INSERT OR IGNORE INTO review_build_selection(
                        thread_id, review_pass_id, repo_full_name, pr_number,
                        reviewed_head_sha, workspace_id, opening_title,
                        selection_policy, spawn_mode, base_repository_id,
                        head_repository_id, base_ref, head_ref,
                        selection_digest, frozen_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                candidate.threadId(), candidate.reviewPassId(),
                candidate.repoFullName(), candidate.prNumber(),
                candidate.reviewedHeadSha(), candidate.spawn().workspaceId(),
                candidate.spawn().openingTitle(),
                candidate.spawn().selectionPolicy().name(),
                candidate.spawn().mode(), candidate.spawn().baseRepositoryId(),
                candidate.spawn().headRepositoryId(), candidate.spawn().baseRef(),
                candidate.spawn().headRef(), candidate.selectionDigest(),
                candidate.frozenAt().toEpochMilli());
        if (inserted == 0) {
            Selection existing = findByReviewPass(reviewPassId)
                    .or(() -> find(threadId))
                    .orElseThrow(() -> new IllegalStateException(
                            "review build selection uniqueness conflict has no owner"));
            if (existing.threadId().equals(threadId)
                    && existing.sameFrozenInput(candidate)) {
                return existing;
            }
            throw new SelectionConflict(
                    existing.threadId(), existing.sameFrozenInput(candidate));
        }

        int position = 0;
        for (Finding finding : candidate.findings()) {
            jdbc.update("""
                        INSERT INTO review_build_selection_item(
                            thread_id, position, review_pass_id, finding_id,
                            finding_revision, content_json, content_digest)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                    candidate.threadId(), ++position, finding.reviewPassId(),
                    finding.findingId(), finding.findingRevision(),
                    finding.contentJson(), finding.contentDigest());
        }
        return candidate;
    }

    public Optional<Selection> find(String threadId)
    {
        requireText(threadId, "threadId");
        return query("selection.thread_id = ?", threadId);
    }

    public Optional<Selection> findByReviewPass(String reviewPassId)
    {
        requireText(reviewPassId, "reviewPassId");
        return query("selection.review_pass_id = ?", reviewPassId);
    }

    /** True only while every mutable source row is the exact frozen revision. */
    boolean matchesCurrent(Selection selection)
    {
        requireNonNull(selection, "selection is null");
        for (Finding frozen : selection.findings()) {
            CurrentFinding current = findCurrent(frozen.findingId()).orElse(null);
            if (current == null
                    || current.revision() != frozen.findingRevision()
                    || !frozen.reviewPassId().equals(
                    current.finding().reviewPassId())
                    || current.finding().status() != ReviewFindingStatus.AGREED) {
                return false;
            }
            String content = snapshot(current.finding());
            if (!content.equals(frozen.contentJson())
                    || !digest(content).equals(frozen.contentDigest())) {
                return false;
            }
        }
        return true;
    }

    private Optional<Selection> query(String predicate, Object value)
    {
        List<SelectionHeader> headers = jdbc.query("""
                SELECT selection.thread_id, selection.review_pass_id,
                       selection.repo_full_name, selection.pr_number,
                       selection.reviewed_head_sha, selection.workspace_id,
                       selection.opening_title, selection.selection_policy,
                       selection.spawn_mode, selection.base_repository_id,
                       selection.head_repository_id, selection.base_ref,
                       selection.head_ref, selection.selection_digest,
                       selection.frozen_at_ms
                FROM review_build_selection selection
                """ + " WHERE " + predicate,
                (rs, row) -> new SelectionHeader(
                        rs.getString("thread_id"),
                        rs.getString("review_pass_id"),
                        rs.getString("repo_full_name"),
                        rs.getInt("pr_number"),
                        rs.getString("reviewed_head_sha"),
                        new SpawnInput(
                                rs.getString("workspace_id"),
                                rs.getString("opening_title"),
                                SelectionPolicy.valueOf(
                                        rs.getString("selection_policy")),
                                rs.getString("spawn_mode"),
                                rs.getString("base_repository_id"),
                                rs.getString("head_repository_id"),
                                rs.getString("base_ref"),
                                rs.getString("head_ref")),
                        rs.getString("selection_digest"),
                        Instant.ofEpochMilli(rs.getLong("frozen_at_ms"))),
                value);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SelectionHeader header = headers.getFirst();
        List<Finding> findings = jdbc.query("""
                SELECT review_pass_id, finding_id, finding_revision,
                       content_json, content_digest
                FROM review_build_selection_item
                WHERE thread_id = ? ORDER BY position
                """, (rs, row) -> new Finding(
                rs.getString("review_pass_id"), rs.getString("finding_id"),
                rs.getInt("finding_revision"), rs.getString("content_json"),
                rs.getString("content_digest")), header.threadId());
        Selection selection = selection(
                header.threadId(), header.reviewPassId(), header.repoFullName(),
                header.prNumber(), header.reviewedHeadSha(), header.spawn(),
                findings, header.frozenAt());
        if (findings.isEmpty()
                || !selection.selectionDigest().equals(header.selectionDigest())
                || findings.stream().anyMatch(finding ->
                !selection.reviewPassId().equals(finding.reviewPassId())
                        || !digest(finding.contentJson())
                        .equals(finding.contentDigest()))) {
            throw new IllegalStateException(
                    "frozen review build selection failed its digest fence");
        }
        return Optional.of(selection);
    }

    private Optional<CurrentFinding> findCurrent(String findingId)
    {
        return jdbc.query("""
                SELECT id, review_pass_id, path, line, severity, status, body,
                       resolution, posted_comment_id, created_at_ms,
                       debate_status, debate_rounds, revision
                FROM review_findings WHERE id = ?
                """, (rs, row) -> new CurrentFinding(
                new ReviewFinding(
                        rs.getString("id"), rs.getString("review_pass_id"),
                        rs.getString("path"), (Integer) rs.getObject("line"),
                        ReviewFindingSeverity.fromDbValue(
                                rs.getString("severity")),
                        ReviewFindingStatus.fromDbValue(rs.getString("status")),
                        rs.getString("body"), rs.getString("resolution"),
                        rs.getString("posted_comment_id"),
                        Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                        rs.getString("debate_status"),
                        rs.getInt("debate_rounds")),
                rs.getInt("revision")), findingId).stream().findFirst();
    }

    private static void requireEligible(
            String reviewPassId, ReviewFinding finding)
    {
        if (!reviewPassId.equals(finding.reviewPassId())) {
            throw new IllegalArgumentException(
                    "review finding belongs to a different pass");
        }
        if (finding.status() != ReviewFindingStatus.AGREED
                || (finding.severity() != ReviewFindingSeverity.BLOCKER
                && finding.severity() != ReviewFindingSeverity.MAJOR)) {
            throw new IllegalArgumentException(
                    "review build finding is not an agreed Major+ finding");
        }
    }

    private Selection selection(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
            SpawnInput spawn,
            List<Finding> findings,
            Instant frozenAt)
    {
        String digest = digest(canonical(List.of(
                reviewPassId, repoFullName.toLowerCase(Locale.ROOT),
                Integer.toString(prNumber), reviewedHeadSha,
                spawn.workspaceId(), spawn.openingTitle(),
                spawn.selectionPolicy().name(), spawn.mode(),
                spawn.baseRepositoryId().toLowerCase(Locale.ROOT),
                spawn.headRepositoryId().toLowerCase(Locale.ROOT),
                spawn.baseRef(), spawn.headRef(),
                findings.stream().map(finding -> canonical(List.of(
                                finding.reviewPassId(), finding.findingId(),
                                Integer.toString(finding.findingRevision()),
                                finding.contentDigest())))
                        .reduce("", (left, right) -> left + right))));
        return new Selection(
                threadId, reviewPassId, repoFullName, prNumber,
                reviewedHeadSha, spawn, digest, List.copyOf(findings), frozenAt);
    }

    private String snapshot(ReviewFinding finding)
    {
        return write(new FindingSnapshot(
                1, finding.id(), finding.reviewPassId(), finding.path(),
                finding.line(), finding.severity().dbValue(),
                finding.status().dbValue(), finding.body(), finding.resolution(),
                finding.postedCommentId(), finding.createdAt(),
                finding.debateStatus(), finding.debateRounds()));
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not freeze review finding", e);
        }
    }

    private static String canonical(List<String> values)
    {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum SelectionPolicy
    {
        ALL_ELIGIBLE,
        EXPLICIT,
    }

    public record SpawnInput(
            String workspaceId,
            String openingTitle,
            SelectionPolicy selectionPolicy,
            String mode,
            String baseRepositoryId,
            String headRepositoryId,
            String baseRef,
            String headRef)
    {
        public SpawnInput
        {
            requireText(workspaceId, "workspaceId");
            requireText(openingTitle, "openingTitle");
            requireNonNull(selectionPolicy, "selectionPolicy is null");
            requireText(mode, "mode");
            if (!ReviewBuildSpawnService.MODE_AUTHOR.equals(mode)
                    && !ReviewBuildSpawnService.MODE_SUGGESTED.equals(mode)) {
                throw new IllegalArgumentException("unknown review build mode");
            }
            requireText(baseRepositoryId, "baseRepositoryId");
            requireText(headRepositoryId, "headRepositoryId");
            requireText(baseRef, "baseRef");
            requireText(headRef, "headRef");
        }
    }

    public record Selection(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
            SpawnInput spawn,
            String selectionDigest,
            List<Finding> findings,
            Instant frozenAt)
    {
        public Selection
        {
            requireText(threadId, "threadId");
            requireText(reviewPassId, "reviewPassId");
            requireText(repoFullName, "repoFullName");
            requireText(reviewedHeadSha, "reviewedHeadSha");
            requireNonNull(spawn, "spawn is null");
            requireText(selectionDigest, "selectionDigest");
            findings = List.copyOf(requireNonNull(findings, "findings is null"));
            requireNonNull(frozenAt, "frozenAt is null");
            if (prNumber < 1 || findings.isEmpty()) {
                throw new IllegalArgumentException(
                        "selection must contain a positive PR and findings");
            }
        }

        boolean sameFrozenInput(Selection other)
        {
            return reviewPassId.equals(other.reviewPassId)
                    && repoFullName.equalsIgnoreCase(other.repoFullName)
                    && prNumber == other.prNumber
                    && reviewedHeadSha.equals(other.reviewedHeadSha)
                    && spawn.equals(other.spawn)
                    && selectionDigest.equals(other.selectionDigest)
                    && findings.equals(other.findings);
        }
    }

    public record Finding(
            String reviewPassId,
            String findingId,
            int findingRevision,
            String contentJson,
            String contentDigest)
    {
        public Finding
        {
            requireText(reviewPassId, "reviewPassId");
            requireText(findingId, "findingId");
            requireText(contentJson, "contentJson");
            requireText(contentDigest, "contentDigest");
            if (findingRevision < 1) {
                throw new IllegalArgumentException(
                        "findingRevision must be positive");
            }
        }
    }

    public static final class SelectionConflict
            extends IllegalStateException
    {
        private final String existingThreadId;
        private final boolean sameInput;

        SelectionConflict(String existingThreadId, boolean sameInput)
        {
            super(sameInput
                    ? "review pass was concurrently frozen by another build Trunk"
                    : "review build selection already exists with different input");
            this.existingThreadId = requireNonNull(
                    existingThreadId, "existingThreadId is null");
            this.sameInput = sameInput;
        }

        public String existingThreadId() { return existingThreadId; }

        public boolean sameInput() { return sameInput; }
    }

    private record SelectionHeader(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
            SpawnInput spawn,
            String selectionDigest,
            Instant frozenAt)
    {}

    private record CurrentFinding(ReviewFinding finding, int revision) {}

    private record FindingSnapshot(
            int schemaVersion,
            String id,
            String reviewPassId,
            String path,
            Integer line,
            String severity,
            String status,
            String body,
            String resolution,
            String postedCommentId,
            Instant createdAt,
            String debateStatus,
            int debateRounds)
    {}

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
