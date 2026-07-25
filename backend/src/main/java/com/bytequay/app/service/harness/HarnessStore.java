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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Event;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** SQLite persistence boundary for harness watches, cycles, failures and KB rules. */
@Repository
public class HarnessStore
{
    private static final int CANDIDATE_PROMOTION_HITS = 3;

    private final JdbcTemplate jdbc;

    public HarnessStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public void insertWatch(Watch watch)
    {
        jdbc.update("""
                INSERT INTO ci_harness_watch (
                    id, workspace_id, owner, repo, pr_number, local_pr_id, local_path,
                    branch, title, status, head_sha, bootstrap_status,
                    bootstrap_profile_json, budget_milli_usd, spent_milli_usd,
                    handoff_json, created_at_ms, updated_at_ms, last_polled_at_ms, stopped_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, watch.id(), watch.workspaceId(), watch.owner(), watch.repo(), watch.prNumber(),
                watch.localPrId(), watch.localPath(), watch.branch(), watch.title(), watch.status().wire(),
                watch.headSha(), watch.bootstrapStatus(), watch.bootstrapProfileJson(),
                watch.budgetMilliUsd(), watch.spentMilliUsd(), watch.handoffJson(),
                watch.createdAtMs(), watch.updatedAtMs(), watch.lastPolledAtMs(), watch.stoppedAtMs());
    }

    public Optional<Watch> findWatch(String id)
    {
        return jdbc.query("SELECT * FROM ci_harness_watch WHERE id = ?", WATCH_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Watch> findLiveWatch(String workspaceId, String owner, String repo, int prNumber)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_watch
                WHERE workspace_id = ? AND owner = ? AND repo = ? AND pr_number = ?
                  AND status != 'stopped'
                ORDER BY created_at_ms DESC LIMIT 1
                """, WATCH_MAPPER, workspaceId, owner, repo, prNumber).stream().findFirst();
    }

    public List<Watch> listWatches(String workspaceId)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_watch WHERE workspace_id = ?
                ORDER BY updated_at_ms DESC
                """, WATCH_MAPPER, workspaceId);
    }

    public List<Watch> pollableWatches(long beforeMs, int limit)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_watch
                WHERE status IN ('watching', 'green', 'handoff')
                  AND (last_polled_at_ms IS NULL OR last_polled_at_ms <= ?)
                ORDER BY COALESCE(last_polled_at_ms, 0), created_at_ms
                LIMIT ?
                """, WATCH_MAPPER, beforeMs, limit);
    }

    public List<Watch> watchesInStatus(WatchStatus status)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_watch WHERE status = ?
                ORDER BY updated_at_ms, id
                """, WATCH_MAPPER, status.wire());
    }

    @Transactional
    public boolean completeWatchBootstrap(
            String id, String profileJson, String localPath, String branch, long nowMs)
    {
        int updated = jdbc.update("""
                UPDATE ci_harness_watch
                SET bootstrap_status = 'ready', bootstrap_profile_json = ?,
                    local_path = COALESCE(?, local_path), branch = COALESCE(?, branch),
                    status = 'watching', last_polled_at_ms = ?, updated_at_ms = ?
                WHERE id = ? AND status = 'bootstrap'
                """, profileJson, localPath, branch, nowMs, nowMs, id);
        if (updated == 0) {
            return false;
        }
        appendEvent(id, null, Phase.PROBE, "bootstrap_complete",
                "Bootstrap profile is ready", profileJson, nowMs);
        return true;
    }

    @Transactional
    public boolean failWatchBootstrap(
            String id, String handoffJson, String detailJson, long nowMs)
    {
        int updated = jdbc.update("""
                UPDATE ci_harness_watch
                SET bootstrap_status = 'failed', status = 'needs_attention',
                    handoff_json = ?, updated_at_ms = ?
                WHERE id = ? AND status = 'bootstrap'
                """, handoffJson, nowMs, id);
        if (updated == 0) {
            return false;
        }
        appendEvent(id, null, Phase.PROBE, "bootstrap_failed",
                "Bootstrap needs attention", detailJson, nowMs);
        return true;
    }

    public void updateWatchStatus(String id, WatchStatus status, String handoffJson, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_watch
                SET status = ?, handoff_json = ?, updated_at_ms = ?,
                    stopped_at_ms = CASE WHEN ? = 'stopped' THEN ? ELSE stopped_at_ms END
                WHERE id = ?
                """, status.wire(), handoffJson, nowMs, status.wire(), nowMs, id);
    }

    public boolean updateWatchStatusIfNotStopped(
            String id, WatchStatus status, String handoffJson, long nowMs)
    {
        return jdbc.update("""
                UPDATE ci_harness_watch
                SET status = ?, handoff_json = ?, updated_at_ms = ?
                WHERE id = ? AND status != 'stopped'
                """, status.wire(), handoffJson, nowMs, id) == 1;
    }

    public boolean updateWatchHeadAndPoll(String id, String headSha, String branch, long nowMs)
    {
        return jdbc.update("""
                UPDATE ci_harness_watch SET head_sha = COALESCE(?, head_sha),
                    branch = COALESCE(?, branch),
                    last_polled_at_ms = ?, updated_at_ms = ?
                WHERE id = ? AND status != 'stopped'
                """, headSha, branch, nowMs, nowMs, id) == 1;
    }

    public void backfillLocalPrId(String id, String localPrId, long nowMs)
    {
        if (localPrId == null || localPrId.isBlank()) {
            return;
        }
        jdbc.update("""
                UPDATE ci_harness_watch SET local_pr_id = ?, updated_at_ms = ?
                WHERE id = ? AND (local_pr_id IS NULL OR local_pr_id = '')
                """, localPrId, nowMs, id);
    }

    public void addWatchCost(String id, long milliUsd, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_watch SET spent_milli_usd = spent_milli_usd + ?,
                    updated_at_ms = ? WHERE id = ?
                """, milliUsd, nowMs, id);
    }

    @Transactional
    public Cycle startCycle(
            String id, String watchId, String triggerKind, String steeringText, long nowMs)
    {
        Optional<Cycle> live = findLiveCycle(watchId);
        if (live.isPresent()) {
            return live.orElseThrow();
        }
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(ordinal), 0) FROM ci_harness_cycle WHERE watch_id = ?",
                Integer.class, watchId);
        int ordinal = (max == null ? 0 : max) + 1;
        try {
            int inserted = jdbc.update("""
                    INSERT INTO ci_harness_cycle (
                        id, watch_id, ordinal, trigger_kind, steering_text, status, phase,
                        cost_milli_usd, started_at_ms, updated_at_ms)
                    SELECT ?, ?, ?, ?, ?, 'queued', 'probe', 0, ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM ci_harness_watch
                        WHERE id = ? AND status != 'stopped')
                    """, id, watchId, ordinal, triggerKind, steeringText, nowMs, nowMs, watchId);
            if (inserted == 0) {
                throw new IllegalStateException("harness watch is stopped");
            }
        }
        catch (DuplicateKeyException ignored) {
            return findLiveCycle(watchId).orElseThrow();
        }
        return findCycle(id).orElseThrow();
    }

    public Optional<Cycle> findCycle(String id)
    {
        return jdbc.query("SELECT * FROM ci_harness_cycle WHERE id = ?", CYCLE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Cycle> findLiveCycle(String watchId)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_cycle WHERE watch_id = ?
                  AND status IN ('queued', 'running')
                ORDER BY started_at_ms DESC LIMIT 1
                """, CYCLE_MAPPER, watchId).stream().findFirst();
    }

    public boolean isCycleActive(String watchId, String cycleId)
    {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM ci_harness_cycle c
                    JOIN ci_harness_watch w ON w.id = c.watch_id
                    WHERE c.id = ? AND c.watch_id = ?
                      AND c.status IN ('queued', 'running')
                      AND w.status != 'stopped')
                """, Boolean.class, cycleId, watchId);
        return Boolean.TRUE.equals(active);
    }

    public List<Cycle> listCycles(String watchId, int limit)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_cycle WHERE watch_id = ?
                ORDER BY ordinal DESC LIMIT ?
                """, CYCLE_MAPPER, watchId, limit);
    }

    public List<Cycle> resumableCycles()
    {
        return jdbc.query("""
                SELECT c.* FROM ci_harness_cycle c
                JOIN ci_harness_watch w ON w.id = c.watch_id
                WHERE c.status IN ('queued', 'running') AND w.status != 'stopped'
                ORDER BY c.started_at_ms
                """, CYCLE_MAPPER);
    }

    public boolean updateCycleProgress(
            String id, CycleStatus status, Phase phase, String headSha,
            String runRef, String runStatusTail, long nowMs)
    {
        return jdbc.update("""
                UPDATE ci_harness_cycle SET status = ?, phase = ?,
                    head_sha = COALESCE(?, head_sha), run_ref = COALESCE(?, run_ref),
                    run_status_tail = COALESCE(?, run_status_tail), updated_at_ms = ?
                WHERE id = ? AND status IN ('queued', 'running')
                  AND EXISTS (
                      SELECT 1 FROM ci_harness_watch w
                      WHERE w.id = ci_harness_cycle.watch_id AND w.status != 'stopped')
                """, status.wire(), phase.wire(), headSha, runRef, runStatusTail, nowMs, id) == 1;
    }

    public void finishCycle(
            String id, CycleStatus status, Phase phase, long costMilliUsd,
            String backupRef, String proofJson, String runStatusTail,
            String errorMessage, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_cycle SET status = ?, phase = ?, cost_milli_usd = ?,
                    backup_ref = COALESCE(?, backup_ref),
                    net_neutral_proof_json = COALESCE(?, net_neutral_proof_json),
                    run_status_tail = COALESCE(?, run_status_tail), error_message = ?,
                    updated_at_ms = ?, finished_at_ms = ? WHERE id = ?
                """, status.wire(), phase.wire(), costMilliUsd, backupRef, proofJson,
                runStatusTail, errorMessage, nowMs, nowMs, id);
    }

    public boolean finishCycleIfLive(
            String id, CycleStatus status, Phase phase, long costMilliUsd,
            String backupRef, String proofJson, String runStatusTail,
            String errorMessage, long nowMs)
    {
        return jdbc.update("""
                UPDATE ci_harness_cycle SET status = ?, phase = ?, cost_milli_usd = ?,
                    backup_ref = COALESCE(?, backup_ref),
                    net_neutral_proof_json = COALESCE(?, net_neutral_proof_json),
                    run_status_tail = COALESCE(?, run_status_tail), error_message = ?,
                    updated_at_ms = ?, finished_at_ms = ?
                WHERE id = ? AND status IN ('queued', 'running')
                  AND EXISTS (
                      SELECT 1 FROM ci_harness_watch w
                      WHERE w.id = ci_harness_cycle.watch_id AND w.status != 'stopped')
                """, status.wire(), phase.wire(), costMilliUsd, backupRef, proofJson,
                runStatusTail, errorMessage, nowMs, nowMs, id) == 1;
    }

    /** The verified rewrite and its user-visible watch state are one durable
     * handoff. A stop racing this transaction makes the whole transition fail. */
    @Transactional
    public boolean finishHandoff(
            String cycleId, String watchId, long costMilliUsd,
            String backupRef, String proofJson, String runStatusTail,
            String handoffJson, long nowMs)
    {
        if (!finishCycleIfLive(cycleId, CycleStatus.HANDOFF, Phase.DONE,
                costMilliUsd, backupRef, proofJson, runStatusTail, null, nowMs)) {
            return false;
        }
        if (!updateWatchStatusIfNotStopped(
                watchId, WatchStatus.HANDOFF, handoffJson, nowMs)) {
            throw new IllegalStateException("harness watch stopped during handoff");
        }
        return true;
    }

    public void addCycleCost(String id, long milliUsd, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_cycle SET cost_milli_usd = cost_milli_usd + ?,
                    updated_at_ms = ? WHERE id = ?
                """, milliUsd, nowMs, id);
    }

    public boolean recordCycleBackupIfLive(
            String id, String backupRef, String originalHead, long nowMs)
    {
        return jdbc.update("""
                UPDATE ci_harness_cycle
                SET backup_ref = ?, original_head = ?, updated_at_ms = ?
                WHERE id = ? AND status IN ('queued', 'running')
                  AND EXISTS (
                      SELECT 1 FROM ci_harness_watch w
                      WHERE w.id = ci_harness_cycle.watch_id AND w.status != 'stopped')
                """, backupRef, originalHead, nowMs, id) == 1;
    }

    public Failure insertFailure(Failure failure)
    {
        jdbc.update("""
                INSERT INTO ci_harness_failure (
                    id, cycle_id, run_id, check_run_id, job_name, module,
                    test_class, test_method, signature, log_excerpt, bucket,
                    rule_id, status, target_subject, diagnosis_json, fix_json,
                    verification_json, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(cycle_id, job_name, signature) DO NOTHING
                """, failure.id(), failure.cycleId(), failure.runId(), failure.checkRunId(),
                failure.jobName(), failure.module(), failure.testClass(), failure.testMethod(),
                failure.signature(), failure.logExcerpt(), failure.bucketLabel(), failure.ruleId(),
                failure.status().wire(), failure.targetSubject(), failure.diagnosisJson(), failure.fixJson(),
                failure.verificationJson(), failure.createdAtMs(), failure.updatedAtMs());
        return jdbc.query("""
                SELECT * FROM ci_harness_failure
                WHERE cycle_id = ? AND job_name = ? AND signature = ?
                """, FAILURE_MAPPER, failure.cycleId(), failure.jobName(), failure.signature())
                .stream().findFirst().orElseThrow();
    }

    public void updateFailure(
            String id, String bucketLabel, String ruleId, FailureStatus status,
            String targetSubject, String diagnosisJson, String fixJson,
            String verificationJson, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_failure SET bucket = ?, rule_id = ?, status = ?,
                    target_subject = COALESCE(?, target_subject),
                    diagnosis_json = COALESCE(?, diagnosis_json),
                    fix_json = COALESCE(?, fix_json),
                    verification_json = COALESCE(?, verification_json), updated_at_ms = ?
                WHERE id = ?
                """, HarnessModels.normalizeBucketLabel(bucketLabel), ruleId, status.wire(),
                targetSubject, diagnosisJson,
                fixJson, verificationJson, nowMs, id);
    }

    public List<Failure> listFailuresForCycle(String cycleId)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_failure WHERE cycle_id = ?
                ORDER BY created_at_ms, id
                """, FAILURE_MAPPER, cycleId);
    }

    public List<Failure> listFailuresForWatch(String watchId, int limit)
    {
        return jdbc.query("""
                SELECT f.* FROM ci_harness_failure f
                JOIN ci_harness_cycle c ON c.id = f.cycle_id
                WHERE c.watch_id = ? ORDER BY f.created_at_ms DESC LIMIT ?
                """, FAILURE_MAPPER, watchId, limit);
    }

    public void appendEvent(
            String watchId, String cycleId, Phase phase, String kind,
            String message, String detailJson, long nowMs)
    {
        jdbc.update("""
                INSERT INTO ci_harness_event (
                    watch_id, cycle_id, phase, kind, message, detail_json, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, watchId, cycleId, phase.wire(), kind, message,
                detailJson == null ? "{}" : detailJson, nowMs);
    }

    public List<Event> listEventsForWatch(String watchId, int limit)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_event WHERE watch_id = ?
                ORDER BY created_at_ms DESC, id DESC LIMIT ?
                """, EVENT_MAPPER, watchId, limit);
    }

    public List<Event> listEventsForCycle(String cycleId)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_event WHERE cycle_id = ?
                ORDER BY created_at_ms, id
                """, EVENT_MAPPER, cycleId);
    }

    public Optional<String> cachedLog(String watchId, String headSha, long checkRunId)
    {
        return jdbc.queryForList("""
                SELECT log_text FROM ci_harness_log_cache
                WHERE watch_id = ? AND head_sha = ? AND check_run_id = ?
                """, String.class, watchId, headSha, checkRunId).stream().findFirst();
    }

    public void cacheLog(String watchId, String headSha, long checkRunId, String logText, long nowMs)
    {
        jdbc.update("""
                INSERT INTO ci_harness_log_cache (
                    watch_id, head_sha, check_run_id, log_text, fetched_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(watch_id, head_sha, check_run_id) DO UPDATE SET
                    log_text = excluded.log_text, fetched_at_ms = excluded.fetched_at_ms
                """, watchId, headSha, checkRunId, logText, nowMs);
    }

    public List<Rule> listRules(String workspaceId, String owner, String repo)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_rule
                WHERE workspace_id = ? AND owner = ? AND repo = ?
                ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'candidate' THEN 1 ELSE 2 END,
                         priority DESC, created_at_ms DESC
                """, RULE_MAPPER, workspaceId, owner, repo);
    }

    public List<Rule> activeRules(String workspaceId, String owner, String repo)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_rule
                WHERE workspace_id = ? AND owner = ? AND repo = ? AND status = 'active'
                ORDER BY CASE WHEN bucket = 'infra' OR bucket LIKE 'infra:%'
                                      OR binding = 'defer' THEN 0 ELSE 1 END,
                         priority DESC, length(matcher_pattern) DESC
                """, RULE_MAPPER, workspaceId, owner, repo);
    }

    public Optional<Rule> findRule(String id)
    {
        return jdbc.query("SELECT * FROM ci_harness_rule WHERE id = ?", RULE_MAPPER, id)
                .stream().findFirst();
    }

    @Transactional
    public Rule upsertCandidate(Rule rule)
    {
        validateCandidate(rule);
        int inserted = jdbc.update("""
                INSERT INTO ci_harness_rule (
                    id, workspace_id, owner, repo, matcher_pattern, scope,
                    bucket, binding, recipe_json, status, origin, priority,
                    evidence_json, hits, created_at_ms, updated_at_ms, approved_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, rule.id(), rule.workspaceId(), rule.owner(), rule.repo(), rule.matcherPattern(),
                rule.scope(), rule.bucketLabel(), rule.binding(), rule.recipeJson(),
                rule.status().wire(), rule.origin(), rule.priority(), rule.evidenceJson(),
                rule.hits(), rule.createdAtMs(), rule.updatedAtMs(), rule.approvedAtMs());
        Rule persisted = findRuleByIdentity(rule).orElseThrow(() ->
                new IllegalStateException("candidate identity collided with another rule"));
        if (inserted == 1) {
            return persisted;
        }
        requireConsistentCandidate(persisted, rule);
        jdbc.update("""
                UPDATE ci_harness_rule SET
                    evidence_json = ?, hits = hits + 1,
                    status = CASE
                        WHEN status = 'candidate' AND hits + 1 >= ? THEN 'active'
                        ELSE status END,
                    approved_at_ms = CASE
                        WHEN status = 'candidate' AND hits + 1 >= ?
                        THEN COALESCE(approved_at_ms, ?)
                        ELSE approved_at_ms END,
                    updated_at_ms = ?
                WHERE id = ?
                """, rule.evidenceJson(), CANDIDATE_PROMOTION_HITS,
                CANDIDATE_PROMOTION_HITS, rule.updatedAtMs(), rule.updatedAtMs(), persisted.id());
        return findRule(persisted.id()).orElseThrow();
    }

    private Optional<Rule> findRuleByIdentity(Rule rule)
    {
        return jdbc.query("""
                SELECT * FROM ci_harness_rule
                WHERE workspace_id = ? AND owner = ? AND repo = ? AND matcher_pattern = ?
                  AND scope IS ?
                """, RULE_MAPPER, rule.workspaceId(), rule.owner(), rule.repo(),
                rule.matcherPattern(), rule.scope()).stream().findFirst();
    }

    /** Records evidence for active rules. Candidate activation happens only
     * through explicit approval or the three-success upsert threshold. */
    @Transactional
    public Rule touchRule(String id, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_rule SET hits = hits + 1,
                    updated_at_ms = ? WHERE id = ?
                """, nowMs, id);
        return findRule(id).orElseThrow();
    }

    public Rule approveRule(String id, long nowMs)
    {
        jdbc.update("""
                UPDATE ci_harness_rule SET status = 'active', origin = 'human',
                    approved_at_ms = ?, updated_at_ms = ?
                WHERE id = ? AND status != 'retired'
                """, nowMs, nowMs, id);
        return findRule(id).orElseThrow();
    }

    public int countRules(String workspaceId, String owner, String repo, RuleStatus status)
    {
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM ci_harness_rule
                WHERE workspace_id = ? AND owner = ? AND repo = ? AND status = ?
                """, Integer.class, workspaceId, owner, repo, status.wire());
        return value == null ? 0 : value;
    }

    private static void validateCandidate(Rule rule)
    {
        boolean recipe = rule.binding() != null
                && rule.binding().matches("recipe:[A-Za-z0-9_.-]+");
        if (!recipe && !"agent".equals(rule.binding())) {
            throw new IllegalArgumentException("candidate binding must be agent or recipe:<id>");
        }
        if (recipe && (rule.recipeJson() == null || rule.recipeJson().isBlank())) {
            throw new IllegalArgumentException("recipe candidate requires a recipe description");
        }
        if (!recipe && rule.recipeJson() != null) {
            throw new IllegalArgumentException("agent candidate cannot carry a recipe description");
        }
    }

    private static void requireConsistentCandidate(Rule persisted, Rule proposed)
    {
        if (!persisted.bucketLabel().equals(proposed.bucketLabel())
                || !persisted.binding().equals(proposed.binding())
                || !Objects.equals(persisted.recipeJson(), proposed.recipeJson())) {
            throw new IllegalStateException(
                    "matching candidate proposals disagree on bucket, binding, or recipe");
        }
    }

    private static final RowMapper<Watch> WATCH_MAPPER = (rs, rowNum) -> new Watch(
            rs.getString("id"), rs.getString("workspace_id"), rs.getString("owner"),
            rs.getString("repo"), rs.getInt("pr_number"), rs.getString("local_pr_id"),
            rs.getString("local_path"), rs.getString("branch"), rs.getString("title"),
            WatchStatus.from(rs.getString("status")), rs.getString("head_sha"),
            rs.getString("bootstrap_status"), rs.getString("bootstrap_profile_json"),
            rs.getLong("budget_milli_usd"), rs.getLong("spent_milli_usd"),
            rs.getString("handoff_json"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"),
            nullableLong(rs, "last_polled_at_ms"), nullableLong(rs, "stopped_at_ms"));

    private static final RowMapper<Cycle> CYCLE_MAPPER = (rs, rowNum) -> new Cycle(
            rs.getString("id"), rs.getString("watch_id"), rs.getInt("ordinal"),
            rs.getString("trigger_kind"), rs.getString("steering_text"),
            CycleStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
            Phase.from(rs.getString("phase")), rs.getString("head_sha"), rs.getString("run_ref"),
            rs.getLong("cost_milli_usd"), rs.getString("backup_ref"),
            rs.getString("original_head"),
            rs.getString("net_neutral_proof_json"), rs.getString("run_status_tail"),
            rs.getLong("started_at_ms"), rs.getLong("updated_at_ms"),
            nullableLong(rs, "finished_at_ms"), rs.getString("error_message"));

    private static final RowMapper<Failure> FAILURE_MAPPER = (rs, rowNum) -> new Failure(
            rs.getString("id"), rs.getString("cycle_id"), rs.getString("run_id"),
            nullableLong(rs, "check_run_id"), rs.getString("job_name"), rs.getString("module"),
            rs.getString("test_class"), rs.getString("test_method"), rs.getString("signature"),
            rs.getString("log_excerpt"), rs.getString("bucket"), rs.getString("rule_id"),
            FailureStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)), rs.getString("target_subject"),
            rs.getString("diagnosis_json"), rs.getString("fix_json"), rs.getString("verification_json"),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private static final RowMapper<Rule> RULE_MAPPER = (rs, rowNum) -> new Rule(
            rs.getString("id"), rs.getString("workspace_id"), rs.getString("owner"),
            rs.getString("repo"), rs.getString("matcher_pattern"), rs.getString("scope"),
            rs.getString("bucket"), rs.getString("binding"), rs.getString("recipe_json"),
            RuleStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)), rs.getString("origin"),
            rs.getInt("priority"), rs.getString("evidence_json"), rs.getInt("hits"),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"), nullableLong(rs, "approved_at_ms"));

    private static final RowMapper<Event> EVENT_MAPPER = (rs, rowNum) -> new Event(
            rs.getLong("id"), rs.getString("watch_id"), rs.getString("cycle_id"),
            Phase.from(rs.getString("phase")), rs.getString("kind"), rs.getString("message"),
            rs.getString("detail_json"), rs.getLong("created_at_ms"));

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
