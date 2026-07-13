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

import com.bytequay.app.domain.InvestigationReviewData.AssignmentBudget;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRelationRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingVerificationRow;
import com.bytequay.app.domain.InvestigationReviewData.HypothesisRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.KnowledgeItemRow;
import com.bytequay.app.domain.InvestigationReviewData.KnowledgeProvenanceRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewAssignmentRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewOutcomeRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewSessionRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compact JDBC store for the typed investigation-review artifact graph. */
@Repository
public class InvestigationReviewStore
{
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InvestigationReviewStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSessionRow> findActiveSessionByPr(String prId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status
                FROM review_session WHERE pr_id = ? AND status IN ('ACTIVE','STALE')
                ORDER BY created_at_ms DESC LIMIT 1
                """, this::session, prId).stream().findFirst();
    }

    /** One-query projection for dashboard review-state badges. */
    @Transactional(readOnly = true)
    public Map<String, String> dashboardStates()
    {
        Map<String, String> states = new HashMap<>();
        jdbc.query("""
                SELECT s.pr_id,
                       CASE WHEN s.status = 'STALE' THEN 'stale'
                            WHEN EXISTS (SELECT 1 FROM review_round r
                                         WHERE r.session_id = s.id AND r.status = 'RUNNING')
                            THEN 'running' ELSE 'done' END AS dashboard_state
                FROM review_session s
                WHERE s.status IN ('ACTIVE','STALE')
                  AND s.id = (SELECT latest.id FROM review_session latest
                              WHERE latest.pr_id = s.pr_id
                                AND latest.status IN ('ACTIVE','STALE')
                              ORDER BY latest.created_at_ms DESC, latest.id DESC LIMIT 1)
                """, (rs, row) -> Map.entry(
                        rs.getString("pr_id"), rs.getString("dashboard_state")))
                .forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(states);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSessionRow> findSession(String sessionId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status
                FROM review_session WHERE id = ?
                """, this::session, sessionId).stream().findFirst();
    }

    @Transactional
    public void insertSession(ReviewSessionRow row, Instant now)
    {
        jdbc.update("""
                INSERT INTO review_session
                (id, repo_id, pr_id, base_commit, reviewed_head_commit, status, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.repoId(), row.prId(), row.baseCommit(), row.reviewedHeadCommit(),
                row.status(), now.toEpochMilli(), now.toEpochMilli());
    }

    @Transactional
    public void updateSessionStatus(String sessionId, String status)
    {
        jdbc.update("UPDATE review_session SET status = ?, updated_at_ms = ? WHERE id = ?",
                status, Instant.now().toEpochMilli(), sessionId);
    }

    @Transactional
    public void updateSessionHead(String sessionId, String reviewedHeadCommit, String status)
    {
        jdbc.update("""
                UPDATE review_session
                SET reviewed_head_commit = ?, status = ?, updated_at_ms = ? WHERE id = ?
                """, reviewedHeadCommit, status, Instant.now().toEpochMilli(), sessionId);
    }

    @Transactional
    public void insertRound(ReviewRoundRow row, Instant now)
    {
        jdbc.update("""
                INSERT INTO review_round
                (id, session_id, agent_run_id, trigger, scope, start_commit, end_commit,
                 status, budget_json, cost_cents, created_at_ms, finished_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, row.id(), row.sessionId(), row.agentRunId(), row.trigger(), row.scope(),
                row.startCommit(), row.endCommit(), row.status(), json(row.budgetJson()),
                row.costCents(), now.toEpochMilli());
    }

    @Transactional
    public void updateRound(String roundId, String status, String endCommit, int costCents)
    {
        boolean terminal = !"RUNNING".equals(status);
        jdbc.update("""
                UPDATE review_round SET status = ?, end_commit = ?, cost_cents = ?, finished_at_ms = ?
                WHERE id = ?
                """, status, endCommit, costCents,
                terminal ? Instant.now().toEpochMilli() : null, roundId);
    }

    @Transactional
    public boolean updateRunningRoundCost(String roundId, int costCents)
    {
        return jdbc.update("""
                UPDATE review_round SET cost_cents = ? WHERE id = ? AND status = 'RUNNING'
                """, costCents, roundId) == 1;
    }

    @Transactional
    public boolean finishRunningRound(
            String roundId, String status, String endCommit, int costCents)
    {
        if ("RUNNING".equals(status)) {
            throw new IllegalArgumentException("terminal round status is required");
        }
        return jdbc.update("""
                UPDATE review_round
                SET status = ?, end_commit = ?, cost_cents = ?, finished_at_ms = ?
                WHERE id = ? AND status = 'RUNNING'
                """, status, endCommit, costCents, Instant.now().toEpochMilli(), roundId) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRoundRow> findRound(String roundId)
    {
        return jdbc.query("SELECT * FROM review_round WHERE id = ?", this::round, roundId)
                .stream().findFirst();
    }

    @Transactional
    public void insertCriterion(CriterionRow row)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO criterion
                (id, repo_id, kind, statement, source_type, source_ref) VALUES (?, ?, ?, ?, ?, ?)
                """, row.id(), row.repoId(), row.kind(), row.statement(), row.sourceType(), row.sourceRef());
    }

    @Transactional
    public void insertObjective(ReviewObjectiveRow row)
    {
        jdbc.update("""
                INSERT INTO review_objective
                (id, round_id, criterion_id, statement, source, applicability_status, resolution_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.roundId(), row.criterionId(), row.statement(), row.source(),
                row.applicabilityStatus(), row.resolutionStatus());
    }

    @Transactional
    public void updateObjectiveResolution(String objectiveId, String status)
    {
        jdbc.update("UPDATE review_objective SET resolution_status = ? WHERE id = ?", status, objectiveId);
    }

    @Transactional(readOnly = true)
    public List<ReviewerDefRow> reviewerDefs()
    {
        return jdbc.query("SELECT * FROM reviewer_def ORDER BY rowid", this::reviewerDef);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewerDefRow> findReviewerDef(String id)
    {
        return jdbc.query("SELECT * FROM reviewer_def WHERE id = ?", this::reviewerDef, id)
                .stream().findFirst();
    }

    @Transactional
    public void upsertReviewerDef(ReviewerDefRow row)
    {
        jdbc.update("""
                INSERT INTO reviewer_def
                (id, name, description, runner, runner_json, persona, eligible_kinds, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,
                runner=excluded.runner, runner_json=excluded.runner_json, persona=excluded.persona,
                eligible_kinds=excluded.eligible_kinds, enabled=excluded.enabled
                """, row.id(), row.name(), row.description(), row.runner(), json(row.runnerJson()),
                row.persona(), json(row.eligibleKinds()), row.enabled() ? 1 : 0);
    }

    /** Preserve historical assignment foreign keys while removing a reviewer from future panels. */
    @Transactional
    public boolean disableReviewerDef(String id)
    {
        return jdbc.update("UPDATE reviewer_def SET enabled = 0 WHERE id = ?", id) > 0;
    }

    @Transactional
    public void insertAssignment(ReviewAssignmentRow row)
    {
        jdbc.update("""
                INSERT INTO review_assignment
                (id, round_id, reviewer_def_id, runner, status, understanding_summary,
                 assumptions_json, unknowns_json, budget_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.roundId(), row.reviewerDefId(), row.runner(), row.status(),
                row.understandingSummary(), json(row.assumptionsJson()), json(row.unknownsJson()),
                json(row.budgetJson()));
    }

    @Transactional
    public void updateAssignment(String assignmentId, String status, String summary,
            List<String> assumptions, List<String> unknowns)
    {
        jdbc.update("""
                UPDATE review_assignment SET status = ?, understanding_summary = ?,
                assumptions_json = ?, unknowns_json = ? WHERE id = ?
                """, status, summary, json(assumptions), json(unknowns), assignmentId);
    }

    @Transactional
    public void insertHypothesis(HypothesisRow row)
    {
        jdbc.update("""
                INSERT INTO hypothesis
                (id, assignment_id, objective_id, claim, origin, status, confidence_class)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.objectiveId(), row.claim(), row.origin(),
                row.status(), row.confidenceClass());
    }

    @Transactional
    public void insertStep(InvestigationStepRow row)
    {
        jdbc.update("""
                INSERT INTO investigation_step
                (id, assignment_id, hypothesis_id, action_type, arguments_json, reason,
                 planned, cost_cents, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.hypothesisId(), row.actionType(),
                json(row.argumentsJson()), row.reason(), row.planned() ? 1 : 0,
                row.costCents(), row.status());
    }

    @Transactional
    public void updateStepStatus(String stepId, String status, int costCents)
    {
        jdbc.update("UPDATE investigation_step SET status = ?, cost_cents = ? WHERE id = ?",
                status, costCents, stepId);
    }

    @Transactional
    public void skipRunningSteps(String assignmentId)
    {
        jdbc.update("UPDATE investigation_step SET status = 'skipped' WHERE assignment_id = ? AND status = 'running'",
                assignmentId);
    }

    @Transactional
    public void insertObservation(ObservationRow row)
    {
        jdbc.update("""
                INSERT INTO observation
                (id, step_id, source_type, commit_sha, path, start_line, end_line, symbol,
                 command, exit_code, artifact_ref, content_digest, preview)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.stepId(), row.sourceType(), row.commitSha(), row.path(),
                row.startLine(), row.endLine(), row.symbol(), row.command(), row.exitCode(),
                row.artifactRef(), row.contentDigest(), row.preview());
    }

    @Transactional
    public void insertFinding(FindingRow row)
    {
        jdbc.update("""
                INSERT INTO finding
                (id, session_id, round_id, objective_id, hypothesis_id, criterion_kind,
                 claim, severity, confidence_class, verification_status, requested_action,
                 lifecycle_status, last_checked_commit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.sessionId(), row.roundId(), row.objectiveId(),
                row.hypothesisId(), row.criterionKind(), row.claim(), row.severity(),
                row.confidenceClass(), row.verificationStatus(), row.requestedAction(),
                row.lifecycleStatus(), row.lastCheckedCommit());
    }

    @Transactional(readOnly = true)
    public Optional<FindingRow> findFinding(String findingId)
    {
        return jdbc.query("SELECT * FROM finding WHERE id = ?", this::finding, findingId)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean assignmentBelongsToSession(String assignmentId, String sessionId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_assignment a
                JOIN review_round r ON r.id = a.round_id
                WHERE a.id = ? AND r.session_id = ?
                """, Integer.class, assignmentId, sessionId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean stepBelongsToAssignment(String stepId, String assignmentId)
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM investigation_step WHERE id = ? AND assignment_id = ?",
                Integer.class, stepId, assignmentId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean hypothesisBelongsToAssignment(String hypothesisId, String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE id = ? AND assignment_id = ?",
                hypothesisId, assignmentId) == 1;
    }

    @Transactional(readOnly = true)
    public boolean observationBelongsToSession(String observationId, String sessionId)
    {
        return count("""
                SELECT COUNT(*) FROM observation o
                JOIN investigation_step s ON s.id = o.step_id
                JOIN review_assignment a ON a.id = s.assignment_id
                JOIN review_round r ON r.id = a.round_id
                WHERE o.id = ? AND r.session_id = ?
                """, observationId, sessionId) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ObservationRow> findObservation(String observationId)
    {
        return jdbc.query("SELECT * FROM observation WHERE id = ?", (rs, n) -> new ObservationRow(
                rs.getString("id"), rs.getString("step_id"), rs.getString("source_type"),
                rs.getString("commit_sha"), rs.getString("path"), integer(rs, "start_line"),
                integer(rs, "end_line"), rs.getString("symbol"), rs.getString("command"),
                integer(rs, "exit_code"), rs.getString("artifact_ref"),
                rs.getString("content_digest"), rs.getString("preview")), observationId)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public int countHypotheses(String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE assignment_id = ?", assignmentId);
    }

    @Transactional(readOnly = true)
    public int countActiveHypotheses(String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE assignment_id = ? AND status = 'active'", assignmentId);
    }

    @Transactional(readOnly = true)
    public int countSteps(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM investigation_step
                WHERE assignment_id = ? AND status <> 'not-applicable'
                  AND action_type NOT LIKE 'sweep:%'
                  AND action_type <> 'user-answer'
                """, assignmentId);
    }

    @Transactional(readOnly = true)
    public int countFindings(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM finding f JOIN hypothesis h ON h.id = f.hypothesis_id
                WHERE h.assignment_id = ?
                """, assignmentId);
    }

    @Transactional
    public void updateFinding(String findingId, String lifecycleStatus, String verificationStatus,
            String confidenceClass, String claim, int severity)
    {
        jdbc.update("""
                UPDATE finding SET lifecycle_status = ?, verification_status = ?,
                confidence_class = ?, claim = ?, severity = ? WHERE id = ?
                """, lifecycleStatus, verificationStatus, confidenceClass, claim, severity, findingId);
    }

    @Transactional
    public void insertEvidence(FindingEvidenceRow row)
    {
        jdbc.update("""
                INSERT INTO finding_evidence
                (finding_id, observation_id, relation, proposition, strength_class,
                 strength_reason, dependency_mode, dependency_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.findingId(), row.observationId(), row.relation(), row.proposition(),
                row.strengthClass(), row.strengthReason(), row.dependencyMode(),
                json(row.dependencyJson()));
    }

    @Transactional
    public void insertVerification(FindingVerificationRow row)
    {
        jdbc.update("""
                INSERT INTO finding_verification
                (id, finding_id, verifier_run_id, evidence_accurate, claim_scope_accurate,
                 severity_accurate, counter_evidence_json, status, confidence_class, explanation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.findingId(), row.verifierRunId(),
                row.evidenceAccurate() ? 1 : 0, row.claimScopeAccurate() ? 1 : 0,
                row.severityAccurate() ? 1 : 0, json(row.counterEvidenceJson()), row.status(),
                row.confidenceClass(), row.explanation());
    }

    @Transactional
    public void insertRelation(FindingRelationRow row)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO finding_relation
                (source_finding_id, target_finding_id, relation) VALUES (?, ?, ?)
                """, row.sourceFindingId(), row.targetFindingId(), row.relation());
    }

    @Transactional
    public void insertOutcome(ReviewOutcomeRow row)
    {
        jdbc.update("""
                INSERT OR REPLACE INTO review_outcome
                (finding_id, user_disposition, author_response, epistemic_resolution,
                 utility_assessment, style_edit_magnitude, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.findingId(), row.userDisposition(), row.authorResponse(),
                row.epistemicResolution(), row.utilityAssessment(), row.styleEditMagnitude(),
                Instant.now().toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<ReviewRoundRow> rounds(String sessionId)
    {
        return jdbc.query("SELECT * FROM review_round WHERE session_id = ? ORDER BY created_at_ms",
                this::round, sessionId);
    }

    @Transactional(readOnly = true)
    public List<CriterionRow> criteria(String sessionId)
    {
        return jdbc.query("""
                SELECT DISTINCT c.* FROM criterion c
                JOIN review_objective o ON o.criterion_id = c.id
                JOIN review_round r ON r.id = o.round_id WHERE r.session_id = ? ORDER BY c.id
                """, (rs, n) -> new CriterionRow(rs.getString("id"), rs.getString("repo_id"),
                rs.getString("kind"), rs.getString("statement"), rs.getString("source_type"),
                rs.getString("source_ref")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<ReviewObjectiveRow> objectives(String sessionId)
    {
        return jdbc.query("""
                SELECT o.* FROM review_objective o JOIN review_round r ON r.id = o.round_id
                WHERE r.session_id = ? ORDER BY o.id
                """, (rs, n) -> new ReviewObjectiveRow(rs.getString("id"), rs.getString("round_id"),
                rs.getString("criterion_id"), rs.getString("statement"), rs.getString("source"),
                rs.getString("applicability_status"), rs.getString("resolution_status")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<ReviewAssignmentRow> assignments(String sessionId)
    {
        return jdbc.query("""
                SELECT a.* FROM review_assignment a JOIN review_round r ON r.id = a.round_id
                WHERE r.session_id = ? ORDER BY a.id
                """, (rs, n) -> new ReviewAssignmentRow(rs.getString("id"), rs.getString("round_id"),
                rs.getString("reviewer_def_id"), rs.getString("runner"), rs.getString("status"),
                rs.getString("understanding_summary"), strings(rs.getString("assumptions_json")),
                strings(rs.getString("unknowns_json")), value(rs.getString("budget_json"), AssignmentBudget.class)),
                sessionId);
    }

    @Transactional(readOnly = true)
    public List<HypothesisRow> hypotheses(String sessionId)
    {
        return jdbc.query("""
                SELECT h.* FROM hypothesis h JOIN review_assignment a ON a.id = h.assignment_id
                JOIN review_round r ON r.id = a.round_id WHERE r.session_id = ? ORDER BY h.id
                """, (rs, n) -> new HypothesisRow(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("objective_id"), rs.getString("claim"), rs.getString("origin"),
                rs.getString("status"), rs.getString("confidence_class")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<InvestigationStepRow> steps(String sessionId)
    {
        return jdbc.query("""
                SELECT s.* FROM investigation_step s JOIN review_assignment a ON a.id = s.assignment_id
                JOIN review_round r ON r.id = a.round_id WHERE r.session_id = ? ORDER BY s.rowid
                """, (rs, n) -> new InvestigationStepRow(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("hypothesis_id"), rs.getString("action_type"), tree(rs.getString("arguments_json")),
                rs.getString("reason"), rs.getInt("planned") != 0, rs.getInt("cost_cents"),
                rs.getString("status")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<ObservationRow> observations(String sessionId)
    {
        return jdbc.query("""
                SELECT o.* FROM observation o JOIN investigation_step s ON s.id = o.step_id
                JOIN review_assignment a ON a.id = s.assignment_id JOIN review_round r ON r.id = a.round_id
                WHERE r.session_id = ? ORDER BY o.rowid
                """, (rs, n) -> new ObservationRow(rs.getString("id"), rs.getString("step_id"),
                rs.getString("source_type"), rs.getString("commit_sha"), rs.getString("path"),
                integer(rs, "start_line"), integer(rs, "end_line"), rs.getString("symbol"),
                rs.getString("command"), integer(rs, "exit_code"), rs.getString("artifact_ref"),
                rs.getString("content_digest"), rs.getString("preview")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<FindingRow> findings(String sessionId)
    {
        return jdbc.query("SELECT * FROM finding WHERE session_id = ? ORDER BY rowid", this::finding, sessionId);
    }

    @Transactional(readOnly = true)
    public List<FindingEvidenceRow> evidence(String sessionId)
    {
        return jdbc.query("""
                SELECT e.* FROM finding_evidence e JOIN finding f ON f.id = e.finding_id
                WHERE f.session_id = ? ORDER BY e.finding_id, e.observation_id
                """, (rs, n) -> new FindingEvidenceRow(rs.getString("finding_id"),
                rs.getString("observation_id"), rs.getString("relation"), rs.getString("proposition"),
                rs.getString("strength_class"), rs.getString("strength_reason"),
                rs.getString("dependency_mode"), tree(rs.getString("dependency_json"))), sessionId);
    }

    @Transactional(readOnly = true)
    public List<FindingVerificationRow> verifications(String sessionId)
    {
        return jdbc.query("""
                SELECT v.* FROM finding_verification v JOIN finding f ON f.id = v.finding_id
                WHERE f.session_id = ? ORDER BY v.rowid
                """, (rs, n) -> new FindingVerificationRow(rs.getString("id"), rs.getString("finding_id"),
                rs.getString("verifier_run_id"), rs.getInt("evidence_accurate") != 0,
                rs.getInt("claim_scope_accurate") != 0, rs.getInt("severity_accurate") != 0,
                strings(rs.getString("counter_evidence_json")), rs.getString("status"),
                rs.getString("confidence_class"), rs.getString("explanation")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<FindingRelationRow> relations(String sessionId)
    {
        return jdbc.query("""
                SELECT rel.* FROM finding_relation rel JOIN finding f ON f.id = rel.source_finding_id
                WHERE f.session_id = ? ORDER BY rel.source_finding_id
                """, (rs, n) -> new FindingRelationRow(rs.getString("source_finding_id"),
                rs.getString("target_finding_id"), rs.getString("relation")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<ReviewOutcomeRow> outcomes(String sessionId)
    {
        return jdbc.query("""
                SELECT o.* FROM review_outcome o JOIN finding f ON f.id = o.finding_id
                WHERE f.session_id = ? ORDER BY o.recorded_at_ms
                """, (rs, n) -> new ReviewOutcomeRow(rs.getString("finding_id"),
                rs.getString("user_disposition"), rs.getString("author_response"),
                rs.getString("epistemic_resolution"), rs.getString("utility_assessment"),
                rs.getInt("style_edit_magnitude")), sessionId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeItemRow> knowledge(String repoId)
    {
        return jdbc.query("SELECT * FROM knowledge_item WHERE repo_id = ? ORDER BY id",
                (rs, n) -> new KnowledgeItemRow(rs.getString("id"), rs.getString("repo_id"),
                        rs.getString("subtype"), rs.getString("statement"),
                        nullableStrings(rs.getString("steps_json")), tree(rs.getString("trigger_json")),
                        rs.getString("state")), repoId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeProvenanceRow> knowledgeProvenance(String repoId)
    {
        return jdbc.query("""
                SELECT p.* FROM knowledge_provenance p JOIN knowledge_item k ON k.id = p.knowledge_item_id
                WHERE k.repo_id = ? ORDER BY p.knowledge_item_id
                """, (rs, n) -> new KnowledgeProvenanceRow(rs.getString("knowledge_item_id"),
                rs.getString("source_kind"), rs.getString("source_ref")), repoId);
    }

    private ReviewSessionRow session(ResultSet rs, int ignored) throws SQLException
    {
        return new ReviewSessionRow(rs.getString("id"), rs.getString("repo_id"),
                rs.getString("pr_id"), rs.getString("base_commit"),
                rs.getString("reviewed_head_commit"), rs.getString("status"));
    }

    private ReviewerDefRow reviewerDef(ResultSet rs, int ignored) throws SQLException
    {
        return new ReviewerDefRow(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("runner"), tree(rs.getString("runner_json")), rs.getString("persona"),
                strings(rs.getString("eligible_kinds")), rs.getInt("enabled") != 0);
    }

    private ReviewRoundRow round(ResultSet rs, int ignored) throws SQLException
    {
        return new ReviewRoundRow(rs.getString("id"), rs.getString("session_id"),
                rs.getString("agent_run_id"), rs.getString("trigger"), rs.getString("scope"),
                rs.getString("start_commit"), rs.getString("end_commit"), rs.getString("status"),
                value(rs.getString("budget_json"), RoundBudget.class), rs.getInt("cost_cents"));
    }

    private FindingRow finding(ResultSet rs, int ignored) throws SQLException
    {
        return new FindingRow(rs.getString("id"), rs.getString("session_id"),
                rs.getString("round_id"), rs.getString("objective_id"),
                rs.getString("hypothesis_id"), rs.getString("criterion_kind"),
                rs.getString("claim"), rs.getInt("severity"), rs.getString("confidence_class"),
                rs.getString("verification_status"), rs.getString("requested_action"),
                rs.getString("lifecycle_status"), rs.getString("last_checked_commit"));
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not encode review artifact", e);
        }
    }

    private <T> T value(String json, Class<T> type)
    {
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review artifact JSON", e);
        }
    }

    private List<String> strings(String json)
    {
        try {
            return mapper.readValue(json, STRING_LIST);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review string list", e);
        }
    }

    private List<String> nullableStrings(String json)
    {
        return json == null ? null : strings(json);
    }

    private JsonNode tree(String json)
    {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review JSON", e);
        }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private int count(String sql, Object... arguments)
    {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }
}
