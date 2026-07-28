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
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Immutable review-session selection consumed by later V2 Task creation. */
@Component
public final class ReviewBuildSelectionStore
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
            List<ReviewFinding> findings,
            Instant frozenAt)
    {
        requireText(threadId, "threadId");
        requireText(reviewPassId, "reviewPassId");
        requireText(repoFullName, "repoFullName");
        requireText(reviewedHeadSha, "reviewedHeadSha");
        requireNonNull(findings, "findings is null");
        requireNonNull(frozenAt, "frozenAt is null");
        if (prNumber < 1 || findings.isEmpty()) {
            throw new IllegalArgumentException(
                    "review build requires a PR and at least one finding");
        }
        List<Finding> frozen = new ArrayList<>(findings.size());
        Set<String> ids = new HashSet<>();
        for (ReviewFinding finding : findings) {
            requireNonNull(finding, "finding is null");
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
            if (!ids.add(finding.id())) {
                throw new IllegalArgumentException(
                        "review finding selection contains a duplicate id");
            }
            String content = write(new FindingSnapshot(
                    1, finding.id(), finding.reviewPassId(), finding.path(),
                    finding.line(), finding.severity().dbValue(),
                    finding.status().dbValue(), finding.body(),
                    finding.resolution(), finding.postedCommentId(),
                    finding.createdAt(), finding.debateStatus(),
                    finding.debateRounds()));
            frozen.add(new Finding(
                    reviewPassId, finding.id(), 1, content, digest(content)));
        }
        Selection candidate = new Selection(
                threadId, reviewPassId, repoFullName, prNumber,
                reviewedHeadSha, digest(frozen.stream()
                        .map(Finding::contentDigest)
                        .reduce("", (left, right) -> left + "\n" + right)),
                List.copyOf(frozen), frozenAt);
        int inserted = jdbc.update("""
                    INSERT OR IGNORE INTO review_build_selection(
                        thread_id, review_pass_id, repo_full_name, pr_number,
                        reviewed_head_sha, selection_digest, frozen_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, candidate.threadId(), candidate.reviewPassId(),
                    candidate.repoFullName(), candidate.prNumber(),
                    candidate.reviewedHeadSha(), candidate.selectionDigest(),
                    candidate.frozenAt().toEpochMilli());
        if (inserted == 0) {
            Selection existing = find(threadId).orElseThrow(() ->
                    new IllegalStateException(
                            "review pass is already frozen for another build Trunk"));
            if (!existing.sameFrozenInput(candidate)) {
                throw new IllegalStateException(
                        "review build selection already exists with different input");
            }
            return existing;
        }
        int position = 0;
        for (Finding finding : candidate.findings()) {
            jdbc.update("""
                        INSERT INTO review_build_selection_item(
                            thread_id, position, review_pass_id, finding_id,
                            finding_revision, content_json, content_digest)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, candidate.threadId(), ++position,
                        finding.reviewPassId(), finding.findingId(),
                        finding.findingRevision(), finding.contentJson(),
                        finding.contentDigest());
        }
        return candidate;
    }

    public Optional<Selection> find(String threadId)
    {
        requireText(threadId, "threadId");
        List<SelectionHeader> headers = jdbc.query("""
                SELECT thread_id, review_pass_id, repo_full_name, pr_number,
                       reviewed_head_sha, selection_digest, frozen_at_ms
                FROM review_build_selection WHERE thread_id = ?
                """, (rs, row) -> new SelectionHeader(
                rs.getString("thread_id"), rs.getString("review_pass_id"),
                rs.getString("repo_full_name"), rs.getInt("pr_number"),
                rs.getString("reviewed_head_sha"),
                rs.getString("selection_digest"),
                Instant.ofEpochMilli(rs.getLong("frozen_at_ms"))), threadId);
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
                rs.getString("content_digest")), threadId);
        Selection selection = new Selection(
                header.threadId(), header.reviewPassId(), header.repoFullName(),
                header.prNumber(), header.reviewedHeadSha(),
                header.selectionDigest(), findings, header.frozenAt());
        String actual = digest(findings.stream().map(Finding::contentDigest)
                .reduce("", (left, right) -> left + "\n" + right));
        if (findings.isEmpty() || !actual.equals(selection.selectionDigest())
                || findings.stream().anyMatch(finding ->
                !selection.reviewPassId().equals(finding.reviewPassId())
                        || !digest(finding.contentJson())
                        .equals(finding.contentDigest()))) {
            throw new IllegalStateException(
                    "frozen review build selection failed its digest fence");
        }
        return Optional.of(selection);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not freeze review finding", e);
        }
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

    public record Selection(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
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
            requireText(selectionDigest, "selectionDigest");
            findings = List.copyOf(requireNonNull(findings, "findings is null"));
            requireNonNull(frozenAt, "frozenAt is null");
            if (prNumber < 1 || findings.isEmpty()) {
                throw new IllegalArgumentException(
                        "selection must contain a positive PR and findings");
            }
        }

        private boolean sameFrozenInput(Selection other)
        {
            return reviewPassId.equals(other.reviewPassId)
                    && repoFullName.equals(other.repoFullName)
                    && prNumber == other.prNumber
                    && reviewedHeadSha.equals(other.reviewedHeadSha)
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

    private record SelectionHeader(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int prNumber,
            String reviewedHeadSha,
            String selectionDigest,
            Instant frozenAt)
    {}

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
