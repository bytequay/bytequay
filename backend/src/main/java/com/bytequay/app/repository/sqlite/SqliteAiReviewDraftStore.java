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

import com.bytequay.app.domain.AiReviewDraft;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.repository.AiReviewDraftStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAiReviewDraftStore
        implements AiReviewDraftStore
{
    private final PrReviewDraftJpaRepository draftRepo;
    private final PrReviewCommentJpaRepository commentRepo;

    public SqliteAiReviewDraftStore(
            PrReviewDraftJpaRepository draftRepo,
            PrReviewCommentJpaRepository commentRepo)
    {
        this.draftRepo = requireNonNull(draftRepo, "draftRepo is null");
        this.commentRepo = requireNonNull(commentRepo, "commentRepo is null");
    }

    @Override
    @Transactional
    public AiReviewDraft save(long prId, String repo, int number, String headSha, ReviewOutput output)
    {
        // Upsert into the active draft so human-staged comments survive a
        // re-run. The active draft is the latest non-PUBLISHED draft for
        // the PR; if the latest is PUBLISHED (or none exists), we start a
        // fresh one.
        PrReviewDraftEntity draft = draftRepo.findTopByPrIdOrderByCreatedAtDesc(prId)
                .filter(d -> !"PUBLISHED".equals(d.getStatus()))
                .orElseGet(() -> {
                    PrReviewDraftEntity fresh = new PrReviewDraftEntity();
                    fresh.setPrId(prId);
                    fresh.setStatus("COMPLETE");
                    return fresh;
                });
        draft.setRepo(repo);
        draft.setPrNumber(number);
        draft.setSummary(output.summary());
        draft.setProviderId(output.providerId());
        draft.setModel(output.modelName());
        draft.setHeadSha(headSha);
        PrReviewDraftEntity saved = draftRepo.save(draft);

        // Replace only AI-source comments — human-staged ones from prior
        // sessions stay attached to the same draft.
        commentRepo.deleteByDraftIdAndSource(saved.getId(), "AI");

        List<PrReviewCommentEntity> commentEntities = output.comments().stream()
                .map(c -> {
                    PrReviewCommentEntity e = new PrReviewCommentEntity();
                    e.setDraftId(saved.getId());
                    e.setFilePath(c.file());
                    e.setLineNumber(c.line());
                    e.setBody(c.body());
                    e.setSeverity(c.severity());
                    e.setSource("AI");
                    e.setSide("RIGHT");
                    return e;
                })
                .collect(toImmutableList());
        commentRepo.saveAll(commentEntities);

        return toDomain(saved, commentRepo.findByDraftIdOrderByIdAsc(saved.getId()));
    }

    @Override
    public Optional<AiReviewDraft> latestForPr(long prId)
    {
        return draftRepo.findTopByPrIdOrderByCreatedAtDesc(prId)
                .map(draft -> toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draft.getId())));
    }

    @Override
    public List<AiReviewDraft> historyForPr(long prId)
    {
        return draftRepo.findByPrIdOrderByCreatedAtDesc(prId).stream()
                .map(d -> toDomain(d, commentRepo.findByDraftIdOrderByIdAsc(d.getId())))
                .collect(toImmutableList());
    }

    @Override
    public Optional<AiReviewDraft> byId(long draftId)
    {
        return draftRepo.findById(draftId)
                .map(d -> toDomain(d, commentRepo.findByDraftIdOrderByIdAsc(d.getId())));
    }

    @Override
    @Transactional
    public AiReviewDraft updateCommentBody(long draftId, long commentId, String editedBody)
    {
        PrReviewCommentEntity comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new IllegalStateException("comment " + commentId + " not found"));
        if (comment.getDraftId() != draftId) {
            throw new IllegalStateException("comment " + commentId + " does not belong to draft " + draftId);
        }
        // Empty / blank string means "clear the edit" — fall back to the
        // original AI body. Anything else is the new edited body.
        if (editedBody == null || editedBody.isBlank()) {
            comment.setEditedBody(null);
        }
        else {
            comment.setEditedBody(editedBody);
        }
        commentRepo.save(comment);
        PrReviewDraftEntity draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("draft " + draftId + " not found"));
        return toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draftId));
    }

    @Override
    @Transactional
    public AiReviewDraft markPublished(long draftId)
    {
        PrReviewDraftEntity entity = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("draft " + draftId + " not found"));
        entity.setStatus("PUBLISHED");
        PrReviewDraftEntity saved = draftRepo.save(entity);
        return toDomain(saved, commentRepo.findByDraftIdOrderByIdAsc(saved.getId()));
    }

    @Override
    @Transactional
    public AiReviewDraft setCommentDismissed(long draftId, long commentId, boolean dismissed)
    {
        PrReviewCommentEntity comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new IllegalStateException("comment " + commentId + " not found"));
        if (comment.getDraftId() != draftId) {
            throw new IllegalStateException("comment " + commentId + " does not belong to draft " + draftId);
        }
        comment.setDismissed(dismissed);
        commentRepo.save(comment);
        PrReviewDraftEntity draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("draft " + draftId + " not found"));
        return toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draftId));
    }

    @Override
    @Transactional
    public AiReviewDraft deleteComment(long draftId, long commentId)
    {
        commentRepo.findById(commentId).ifPresent(c -> {
            if (c.getDraftId() != draftId) {
                throw new IllegalStateException("comment " + commentId + " does not belong to draft " + draftId);
            }
            commentRepo.delete(c);
        });
        PrReviewDraftEntity draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("draft " + draftId + " not found"));
        return toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draftId));
    }

    @Override
    @Transactional
    public void delete(long draftId)
    {
        commentRepo.deleteByDraftId(draftId);
        draftRepo.deleteById(draftId);
    }

    @Override
    @Transactional
    public AiReviewDraft findOrCreateActive(long prId, String repo, int number, String headSha)
    {
        // The active draft is the latest non-PUBLISHED draft for the PR.
        // The AI run path also reuses the same lookup so a fresh AI run
        // doesn't shadow staged human comments.
        Optional<PrReviewDraftEntity> existing = draftRepo.findTopByPrIdOrderByCreatedAtDesc(prId)
                .filter(d -> !"PUBLISHED".equals(d.getStatus()));
        PrReviewDraftEntity draft = existing.orElseGet(() -> {
            PrReviewDraftEntity fresh = new PrReviewDraftEntity();
            fresh.setPrId(prId);
            fresh.setRepo(repo);
            fresh.setPrNumber(number);
            fresh.setSummary(null);
            // Provider/model are required NOT NULL; for human-only drafts
            // we have no AI provider yet, so seed with an explicit marker
            // that the publish path treats as "no AI run attached".
            fresh.setProviderId("none");
            fresh.setModel("none");
            fresh.setHeadSha(headSha);
            fresh.setStatus("COMPLETE");
            return draftRepo.save(fresh);
        });
        return toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draft.getId()));
    }

    @Override
    @Transactional
    public AiReviewDraft stageHumanComment(
            long draftId,
            String filePath,
            int lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String body)
    {
        PrReviewDraftEntity draft = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("draft " + draftId + " not found"));
        if ("PUBLISHED".equals(draft.getStatus())) {
            throw new IllegalStateException("draft " + draftId + " is published — staging is closed");
        }
        PrReviewCommentEntity e = new PrReviewCommentEntity();
        e.setDraftId(draftId);
        e.setFilePath(filePath);
        e.setLineNumber(lineNumber);
        e.setBody(body);
        // 'suggestion' is the no-prefix sentinel formatCommentBody already
        // honours, so we reuse it for human-authored comments rather than
        // adding another null/empty branch.
        e.setSeverity("suggestion");
        e.setSource("HUMAN");
        e.setSide(side != null ? side : "RIGHT");
        e.setStartLine(startLine);
        e.setStartSide(startSide);
        commentRepo.save(e);
        return toDomain(draft, commentRepo.findByDraftIdOrderByIdAsc(draftId));
    }

    private static AiReviewDraft toDomain(PrReviewDraftEntity draft, List<PrReviewCommentEntity> comments)
    {
        List<AiReviewDraft.DraftComment> domainComments = comments.stream()
                .map(c -> new AiReviewDraft.DraftComment(
                        c.getId(),
                        c.getFilePath(),
                        c.getLineNumber(),
                        c.getBody(),
                        c.getEditedBody(),
                        c.getSeverity(),
                        c.isDismissed(),
                        c.getSource(),
                        c.getSide(),
                        c.getStartLine(),
                        c.getStartSide()))
                .collect(toImmutableList());
        return new AiReviewDraft(
                draft.getId(),
                draft.getPrId(),
                draft.getRepo(),
                draft.getPrNumber(),
                draft.getSummary(),
                draft.getProviderId(),
                draft.getModel(),
                draft.getHeadSha(),
                draft.getStatus(),
                draft.getCreatedAt(),
                draft.getUpdatedAt(),
                domainComments);
    }
}
