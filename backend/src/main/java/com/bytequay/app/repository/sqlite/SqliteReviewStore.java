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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.repository.ReviewStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteReviewStore
        implements ReviewStore
{
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ReviewPassJpaRepository passes;
    private final ReviewParticipantJpaRepository participants;
    private final ReviewMessageJpaRepository messages;
    private final ReviewFindingJpaRepository findings;
    private final ObjectMapper mapper;

    SqliteReviewStore(
            ReviewPassJpaRepository passes,
            ReviewParticipantJpaRepository participants,
            ReviewMessageJpaRepository messages,
            ReviewFindingJpaRepository findings,
            ObjectMapper mapper)
    {
        this.passes = requireNonNull(passes, "passes is null");
        this.participants = requireNonNull(participants, "participants is null");
        this.messages = requireNonNull(messages, "messages is null");
        this.findings = requireNonNull(findings, "findings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    // ── passes ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void savePass(ReviewPass pass)
    {
        requireNonNull(pass, "pass is null");
        ReviewPassEntity entity = passes.findById(pass.id()).orElseGet(ReviewPassEntity::new);
        entity.setId(pass.id());
        entity.setThreadId(pass.threadId());
        entity.setRepoFullName(pass.repoFullName());
        entity.setPrNumber(pass.prNumber());
        entity.setHeadSha(pass.headSha());
        entity.setPhase(pass.phase().dbValue());
        entity.setRound(pass.round());
        entity.setRoundCap(pass.roundCap());
        entity.setCostCapMilli(pass.costCapMilli());
        entity.setCostUsdMilli(pass.costUsdMilli());
        entity.setVerdict(pass.verdict() == null ? null : pass.verdict().dbValue());
        entity.setCreatedAtMs(pass.createdAt().toEpochMilli());
        entity.setEndedAtMs(pass.endedAt() == null ? null : pass.endedAt().toEpochMilli());
        passes.save(entity);
    }

    @Override
    public Optional<ReviewPass> findPassById(String id)
    {
        return passes.findById(id).map(SqliteReviewStore::toPass);
    }

    @Override
    public List<ReviewPass> listPassesByThread(String threadId)
    {
        return passes.findByThreadIdOrderByCreatedAtMsAsc(threadId).stream()
                .map(SqliteReviewStore::toPass)
                .toList();
    }

    @Override
    public List<ReviewPass> listPassesForPr(String repoFullName, int prNumber)
    {
        return passes.findByRepoFullNameAndPrNumberOrderByCreatedAtMsDesc(repoFullName, prNumber)
                .stream()
                .map(SqliteReviewStore::toPass)
                .toList();
    }

    @Override
    @Transactional
    public void deletePass(String id)
    {
        if (passes.existsById(id)) {
            passes.deleteById(id);
        }
    }

    // ── participants ─────────────────────────────────────────────────

    @Override
    @Transactional
    public void saveParticipant(ReviewParticipant participant)
    {
        requireNonNull(participant, "participant is null");
        ReviewParticipantEntity entity = participants.findById(participant.id())
                .orElseGet(ReviewParticipantEntity::new);
        entity.setId(participant.id());
        entity.setReviewPassId(participant.reviewPassId());
        entity.setKind(participant.kind().dbValue());
        entity.setCredentialId(participant.credentialId());
        entity.setPersonaLabel(participant.personaLabel());
        entity.setModel(participant.model());
        entity.setColor(participant.color());
        entity.setCreatedAtMs(participant.createdAt().toEpochMilli());
        participants.save(entity);
    }

    @Override
    public Optional<ReviewParticipant> findParticipantById(String id)
    {
        return participants.findById(id).map(SqliteReviewStore::toParticipant);
    }

    @Override
    public List<ReviewParticipant> listParticipantsForPass(String reviewPassId)
    {
        return participants.findByReviewPassIdOrderByCreatedAtMsAsc(reviewPassId).stream()
                .map(SqliteReviewStore::toParticipant)
                .toList();
    }

    // ── messages ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void saveMessage(ReviewMessage message)
    {
        requireNonNull(message, "message is null");
        ReviewMessageEntity entity = messages.findById(message.id())
                .orElseGet(ReviewMessageEntity::new);
        entity.setId(message.id());
        entity.setReviewPassId(message.reviewPassId());
        entity.setParticipantId(message.participantId());
        entity.setPhase(message.phase().dbValue());
        entity.setRound(message.round());
        entity.setBody(message.body());
        entity.setMentionsJson(writeStringList(message.mentions()));
        entity.setRefsJson(writeStringList(message.refs()));
        entity.setPayloadKind(message.payloadKind());
        entity.setPayloadJson(message.payloadJson());
        entity.setCostUsdMilli(message.costUsdMilli());
        entity.setCreatedAtMs(message.createdAt().toEpochMilli());
        messages.save(entity);
    }

    @Override
    public Optional<ReviewMessage> findMessageById(String id)
    {
        return messages.findById(id).map(this::toMessage);
    }

    @Override
    public List<ReviewMessage> listMessagesForPass(String reviewPassId)
    {
        return messages.findByReviewPassIdOrderByCreatedAtMsAsc(reviewPassId).stream()
                .map(this::toMessage)
                .toList();
    }

    // ── findings ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void saveFinding(ReviewFinding finding)
    {
        requireNonNull(finding, "finding is null");
        ReviewFindingEntity entity = findings.findById(finding.id())
                .orElseGet(ReviewFindingEntity::new);
        entity.setId(finding.id());
        entity.setReviewPassId(finding.reviewPassId());
        entity.setPath(finding.path());
        entity.setLine(finding.line());
        entity.setSeverity(finding.severity().dbValue());
        entity.setStatus(finding.status().dbValue());
        entity.setBody(finding.body());
        entity.setResolution(finding.resolution());
        entity.setPostedCommentId(finding.postedCommentId());
        entity.setDebateStatus(finding.debateStatus());
        entity.setDebateRounds(finding.debateRounds());
        entity.setCreatedAtMs(finding.createdAt().toEpochMilli());
        findings.save(entity);
    }

    @Override
    public Optional<ReviewFinding> findFindingById(String id)
    {
        return findings.findById(id).map(SqliteReviewStore::toFinding);
    }

    @Override
    public List<ReviewFinding> listFindingsForPass(String reviewPassId)
    {
        return findings.findByReviewPassIdOrderByCreatedAtMsAsc(reviewPassId).stream()
                .map(SqliteReviewStore::toFinding)
                .toList();
    }

    // ── mappers ──────────────────────────────────────────────────────

    private static ReviewPass toPass(ReviewPassEntity e)
    {
        return new ReviewPass(
                e.getId(),
                e.getThreadId(),
                e.getRepoFullName(),
                e.getPrNumber(),
                e.getHeadSha(),
                ReviewPhase.fromDbValue(e.getPhase()),
                e.getRound(),
                e.getRoundCap(),
                e.getCostCapMilli(),
                e.getCostUsdMilli(),
                ReviewVerdict.fromDbValue(e.getVerdict()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getEndedAtMs() == null ? null : Instant.ofEpochMilli(e.getEndedAtMs()));
    }

    private static ReviewParticipant toParticipant(ReviewParticipantEntity e)
    {
        return new ReviewParticipant(
                e.getId(),
                e.getReviewPassId(),
                ReviewParticipantKind.fromDbValue(e.getKind()),
                e.getCredentialId(),
                e.getPersonaLabel(),
                e.getModel(),
                e.getColor(),
                Instant.ofEpochMilli(e.getCreatedAtMs()));
    }

    private ReviewMessage toMessage(ReviewMessageEntity e)
    {
        return new ReviewMessage(
                e.getId(),
                e.getReviewPassId(),
                e.getParticipantId(),
                ReviewPhase.fromDbValue(e.getPhase()),
                e.getRound(),
                e.getBody(),
                readStringList(e.getMentionsJson()),
                readStringList(e.getRefsJson()),
                e.getPayloadKind() == null ? "prose" : e.getPayloadKind(),
                e.getPayloadJson(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getCreatedAtMs()));
    }

    private static ReviewFinding toFinding(ReviewFindingEntity e)
    {
        return new ReviewFinding(
                e.getId(),
                e.getReviewPassId(),
                e.getPath(),
                e.getLine(),
                ReviewFindingSeverity.fromDbValue(e.getSeverity()),
                ReviewFindingStatus.fromDbValue(e.getStatus()),
                e.getBody(),
                e.getResolution(),
                e.getPostedCommentId(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getDebateStatus(),
                e.getDebateRounds());
    }

    private String writeStringList(List<String> values)
    {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(values);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "review-message string list could not be serialised", e);
        }
    }

    private List<String> readStringList(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, STRING_LIST);
        }
        catch (JsonProcessingException e) {
            // Don't surface a parse failure as a 500 from a listMessages
            // call — log-and-fall-back keeps the transcript readable even
            // if one row's JSON column got truncated by hand.
            return List.of();
        }
    }
}
