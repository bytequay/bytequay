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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.github.GitHubEffectRecords.EffectKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.StepKind;
import com.bytequay.app.flow.runtime.FlowRuntime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Concrete owner of immutable, ordered GitHub effect plans. */
public final class GitHubEffects
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;

    public GitHubEffects(DataSource dataSource, FlowRuntime runtime)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public static final class PreparedCiUpdatePlan
    {
        private final ExternalEffectPlan plan;
        private final ExternalEffectStep step;

        private PreparedCiUpdatePlan(
                ExternalEffectPlan plan, ExternalEffectStep step)
        {
            this.plan = plan;
            this.step = step;
        }

        public ExternalEffectPlan plan()
        {
            return plan;
        }

        public ExternalEffectStep step()
        {
            return step;
        }
    }

    public PreparedCiUpdatePlan prepareCiUpdatePlan(
            String authorizationId,
            String operationId,
            String prId,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            String actionRef,
            String actionDigest,
            String requiredCiPolicyRevisionId,
            Instant createdAt)
    {
        requireText(authorizationId, "authorizationId");
        requireText(operationId, "operationId");
        requireText(prId, "prId");
        requireText(branchRef, "branchRef");
        requireText(expectedRemoteHead, "expectedRemoteHead");
        requireText(proposedHead, "proposedHead");
        requireText(actionRef, "actionRef");
        requireText(actionDigest, "actionDigest");
        requireText(requiredCiPolicyRevisionId,
                "requiredCiPolicyRevisionId");
        requireNonNull(createdAt, "createdAt is null");
        return inTransaction(() -> {
            runtime.lockPublishedPullRequest(prId);
            Long sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(pr_sequence), 0) + 1 "
                            + "FROM flow_github_external_effect_plan "
                            + "WHERE pr_id = ?",
                    Long.class,
                    prId);
            long prSequence = requireNonNull(
                    sequence, "prSequence is null");
            String planId = stableId(
                    "github-effect-plan:v1", authorizationId);
            String stepId = stableId(
                    "github-effect-step:v1", planId, "1");
            String preconditionDigest = stableId(
                    "github-push-precondition:v1",
                    branchRef,
                    expectedRemoteHead,
                    proposedHead,
                    "force:false");
            ExternalEffectStep step = new ExternalEffectStep(
                    stepId,
                    planId,
                    1,
                    StepKind.PUSH_EXACT,
                    branchRef,
                    expectedRemoteHead,
                    proposedHead,
                    false,
                    actionRef,
                    actionDigest,
                    preconditionDigest);
            String planDigest = stableId(
                    "github-effect-plan-digest:v1",
                    planId,
                    operationId,
                    authorizationId,
                    prId,
                    Long.toString(prSequence),
                    EffectKind.CI_UPDATE.name(),
                    expectedRemoteHead,
                    actionRef,
                    actionDigest,
                    requiredCiPolicyRevisionId,
                    "step-count:1",
                    step.stepId(),
                    step.kind().name(),
                    step.preconditionDigest());
            return new PreparedCiUpdatePlan(
                    new ExternalEffectPlan(
                            planId,
                            operationId,
                            authorizationId,
                            prId,
                            prSequence,
                            EffectKind.CI_UPDATE,
                            expectedRemoteHead,
                            actionRef,
                            actionDigest,
                            requiredCiPolicyRevisionId,
                            planDigest,
                            createdAt),
                    step);
        });
    }

    public ExternalEffectPlan insert(PreparedCiUpdatePlan prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            ExternalEffectPlan plan = prepared.plan;
            ExternalEffectStep step = prepared.step;
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_plan (
                        plan_id, operation_id, authorization_id, pr_id,
                        pr_sequence, kind, expected_remote_head, action_ref,
                        action_digest, required_ci_policy_revision_id,
                        plan_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'CI_UPDATE', ?, ?, ?, ?, ?, ?)
                    """,
                    plan.planId(),
                    plan.operationId(),
                    plan.authorizationId(),
                    plan.prId(),
                    plan.prSequence(),
                    plan.expectedRemoteHead(),
                    plan.actionRef(),
                    plan.actionDigest(),
                    plan.requiredCiPolicyRevisionId(),
                    plan.planDigest(),
                    plan.createdAt().toEpochMilli());
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_step (
                        step_id, plan_id, ordinal, kind, branch_ref,
                        expected_remote_head, proposed_head, force_push,
                        action_ref, action_digest, precondition_digest
                    ) VALUES (?, ?, 1, 'PUSH_EXACT', ?, ?, ?, 0, ?, ?, ?)
                    """,
                    step.stepId(),
                    step.planId(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    step.actionRef(),
                    step.actionDigest(),
                    step.preconditionDigest());
            return requirePlan(plan.planId());
        });
    }

    public Optional<ExternalEffectPlan> plan(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_plan WHERE plan_id = ?",
                (result, row) -> readPlan(result),
                planId).stream().findFirst();
    }

    public Optional<ExternalEffectPlan> planForAuthorization(
            String authorizationId)
    {
        requireText(authorizationId, "authorizationId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_plan "
                        + "WHERE authorization_id = ?",
                (result, row) -> readPlan(result),
                authorizationId).stream().findFirst();
    }

    public List<ExternalEffectStep> steps(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_step "
                        + "WHERE plan_id = ? ORDER BY ordinal",
                (result, row) -> readStep(result),
                planId);
    }

    /** Recomputes the immutable one-step plan digests on every graph read. */
    public void assertExactPlan(
            ExternalEffectPlan plan, List<ExternalEffectStep> steps)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(steps, "steps is null");
        if (steps.size() != 1) {
            throw new IllegalStateException(
                    "CI_UPDATE plan must contain exactly one step");
        }
        ExternalEffectStep step = steps.getFirst();
        String planId = stableId(
                "github-effect-plan:v1", plan.authorizationId());
        String stepId = stableId(
                "github-effect-step:v1", plan.planId(), "1");
        String preconditionDigest = stableId(
                "github-push-precondition:v1",
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
                "force:false");
        String planDigest = stableId(
                "github-effect-plan-digest:v1",
                plan.planId(),
                plan.operationId(),
                plan.authorizationId(),
                plan.prId(),
                Long.toString(plan.prSequence()),
                plan.kind().name(),
                plan.expectedRemoteHead(),
                plan.actionRef(),
                plan.actionDigest(),
                plan.requiredCiPolicyRevisionId(),
                "step-count:1",
                step.stepId(),
                step.kind().name(),
                step.preconditionDigest());
        if (!plan.planId().equals(planId)
                || !step.planId().equals(plan.planId())
                || !step.stepId().equals(stepId)
                || step.ordinal() != 1
                || step.kind() != StepKind.PUSH_EXACT
                || step.forcePush()
                || !step.expectedRemoteHead().equals(
                        plan.expectedRemoteHead())
                || !step.actionRef().equals(plan.actionRef())
                || !step.actionDigest().equals(plan.actionDigest())
                || !step.preconditionDigest().equals(preconditionDigest)
                || !plan.planDigest().equals(planDigest)) {
            throw new IllegalStateException(
                    "GitHub effect plan digest graph is invalid");
        }
    }

    private ExternalEffectPlan requirePlan(String planId)
    {
        return plan(planId).orElseThrow(() ->
                new IllegalStateException("GitHub effect plan is missing"));
    }

    private static ExternalEffectPlan readPlan(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectPlan(
                result.getString("plan_id"),
                result.getString("operation_id"),
                result.getString("authorization_id"),
                result.getString("pr_id"),
                result.getLong("pr_sequence"),
                EffectKind.valueOf(result.getString("kind")),
                result.getString("expected_remote_head"),
                result.getString("action_ref"),
                result.getString("action_digest"),
                result.getString("required_ci_policy_revision_id"),
                result.getString("plan_digest"),
                Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static ExternalEffectStep readStep(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectStep(
                result.getString("step_id"),
                result.getString("plan_id"),
                result.getInt("ordinal"),
                StepKind.valueOf(result.getString("kind")),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("proposed_head"),
                result.getInt("force_push") != 0,
                result.getString("action_ref"),
                result.getString("action_digest"),
                result.getString("precondition_digest"));
    }

    private static String stableId(String... fields)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return requireNonNull(
                transactions.execute(ignored -> work.get()),
                "transaction returned null");
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
