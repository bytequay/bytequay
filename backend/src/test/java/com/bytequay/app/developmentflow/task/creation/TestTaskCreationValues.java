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
package com.bytequay.app.developmentflow.task.creation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.EXISTING_PR_HEAD;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.FRESH_REMOTE_BASE;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.BaseSource.PLANNING_SNAPSHOT;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.AGENT_HANDOFF;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.AUTOMATION;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.DIRECT_USER;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.ISSUE_MONITOR;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.QUALITY_SCAN;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.CreationProvenance.REVIEW_SESSION;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.Kind.EXISTING_OWN_PR;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.Kind.NEW_FROM_TRUNK;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.Kind.REVIEW_FINDINGS;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.RepositoryRoute.DIRECT;
import static com.bytequay.app.developmentflow.task.creation.TaskAssignment.RepositoryRoute.FORK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTaskCreationValues
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void representsAllSixAssignmentVariantsWithoutNullableShapeInference()
    {
        TaskAssignment.NewFromTrunk agent = new TaskAssignment.NewFromTrunk(
                identity("agent"), new TaskAssignment.AgentHandoff("base-1"),
                "seed", "implement it");
        TaskAssignment.NewFromTrunk user = new TaskAssignment.NewFromTrunk(
                identity("user"), new TaskAssignment.DirectUser(),
                "seed", "implement it");
        TaskAssignment.ExistingOwnPr existing = new TaskAssignment.ExistingOwnPr(
                identity("pr"), directPr());
        TaskAssignment.ReviewFindings review = new TaskAssignment.ReviewFindings(
                identity("review"), "review-1", directPr(),
                List.of(finding("review-1", "finding-1")));
        TaskAssignment.Issue issue = new TaskAssignment.Issue(
                identity("issue"), "acme/widget#42");
        TaskAssignment.Automation automation = new TaskAssignment.Automation(
                identity("automation"), "nightly", "dependency refresh");
        TaskAssignment.QualityScan quality = new TaskAssignment.QualityScan(
                identity("quality"), "scan-evidence-1");

        assertThat(agent.kind()).isEqualTo(NEW_FROM_TRUNK);
        assertThat(agent.provenance()).isEqualTo(AGENT_HANDOFF);
        assertThat(agent.baseSource()).isEqualTo(PLANNING_SNAPSHOT);
        assertThat(user.provenance()).isEqualTo(DIRECT_USER);
        assertThat(user.baseSource()).isEqualTo(FRESH_REMOTE_BASE);
        assertThat(existing.kind()).isEqualTo(EXISTING_OWN_PR);
        assertThat(existing.baseSource()).isEqualTo(EXISTING_PR_HEAD);
        assertThat(review.kind()).isEqualTo(REVIEW_FINDINGS);
        assertThat(review.provenance()).isEqualTo(REVIEW_SESSION);
        assertThat(issue.provenance()).isEqualTo(ISSUE_MONITOR);
        assertThat(automation.provenance()).isEqualTo(AUTOMATION);
        assertThat(quality.provenance()).isEqualTo(QUALITY_SCAN);
    }

    @Test
    void freezesExactDirectAndForkPullRequestRoutes()
    {
        TaskAssignment.PullRequestRef direct = directPr();
        TaskAssignment.PullRequestRef fork = new TaskAssignment.PullRequestRef(
                new TaskAssignment.Fork("acme/widget", "jack/widget"),
                43, "main", "feature/fork", "base-2", "head-2");

        assertThat(direct.repositories().route()).isEqualTo(DIRECT);
        assertThat(direct.repositories().repositoryId()).isEqualTo("acme/widget");
        assertThat(direct.repositories().baseRepositoryId()).isEqualTo("acme/widget");
        assertThat(direct.repositories().upstreamRepositoryId()).isEmpty();
        assertThat(fork.repositories().route()).isEqualTo(FORK);
        assertThat(fork.repositories().repositoryId()).isEqualTo("jack/widget");
        assertThat(fork.repositories().baseRepositoryId()).isEqualTo("acme/widget");
        assertThat(fork.repositories().upstreamRepositoryId())
                .contains("acme/widget");
        assertThatThrownBy(() -> new TaskAssignment.Fork(
                "Acme/Widget", "acme/widget"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void reviewFindingsAreOrderedImmutableAndExact()
    {
        List<TaskAssignment.ReviewFindingRef> selected = new ArrayList<>(List.of(
                finding("review-1", "finding-1"),
                finding("review-1", "finding-2")));
        TaskAssignment.ReviewFindings assignment = new TaskAssignment.ReviewFindings(
                identity("review"), "review-1", directPr(), selected);
        selected.clear();

        assertThat(assignment.findings())
                .extracting(TaskAssignment.ReviewFindingRef::findingId)
                .containsExactly("finding-1", "finding-2");
        assertThatThrownBy(() -> assignment.findings().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new TaskAssignment.ReviewFindings(
                identity("empty"), "review-1", directPr(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> new TaskAssignment.ReviewFindings(
                identity("duplicate"), "review-1", directPr(), List.of(
                        finding("review-1", "finding-1"),
                        new TaskAssignment.ReviewFindingRef(
                                "review-1", "finding-1", 3, "digest-new"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new TaskAssignment.ReviewFindings(
                identity("wrong-source"), "review-1", directPr(),
                List.of(finding("review-2", "finding-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void creationInputRejectsMismatchedBaseHeadAndPolicyShapes()
    {
        TaskAssignment.NewFromTrunk agent = new TaskAssignment.NewFromTrunk(
                identity("agent"), new TaskAssignment.AgentHandoff("base-1"),
                "seed", "prompt");
        TaskCreationInput valid = input(agent, new TaskCreationInput.PlanningSnapshot(
                new TaskAssignment.Direct("acme/widget"), "main", "base-1"));
        assertThat(valid.provenance()).isEqualTo(AGENT_HANDOFF);

        assertThatThrownBy(() -> input(agent, new TaskCreationInput.PlanningSnapshot(
                new TaskAssignment.Direct("acme/widget"), "main", "other-base")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved Trunk snapshot");

        TaskAssignment.ExistingOwnPr existing = new TaskAssignment.ExistingOwnPr(
                identity("existing"), directPr());
        TaskAssignment.PullRequestRef movedHead = new TaskAssignment.PullRequestRef(
                new TaskAssignment.Direct("acme/widget"), 42,
                "main", "feature/existing", "base-1", "head-moved");
        assertThatThrownBy(() -> input(
                existing, new TaskCreationInput.ExistingPrHead(movedHead)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignment head");

        TaskCreationInput.TaskPolicy wrongTrunk = policy("other-trunk", true, true);
        assertThatThrownBy(() -> new TaskCreationInput(
                "workspace-1", agent, wrongTrunk,
                new TaskCreationInput.PlanningSnapshot(
                        new TaskAssignment.Direct("acme/widget"), "main", "base-1"),
                engine(), workModel(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy Trunk");
        assertThatThrownBy(() -> policy("trunk-1", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autoMerge");
    }

    @Test
    void derivesExactNormalizedProvisionTargetWithoutGeneratingTaskId()
    {
        ProvisionTarget direct = ProvisionTarget.derive(
                "t260728-1-a1.k2",
                Path.of("/tmp/repo/sub/.."),
                new TaskAssignment.Direct("acme/widget"));
        ProvisionTarget fork = ProvisionTarget.derive(
                "t260728-1-a1.k3",
                Path.of("/tmp/fork"),
                new TaskAssignment.Fork("acme/widget", "jack/widget"));

        assertThat(direct.branchName()).isEqualTo("dev/t260728-1-a1.k2");
        assertThat(direct.worktreePath())
                .isEqualTo(Path.of("/tmp/repo/.worktrees/t260728-1-a1.k2"));
        assertThat(direct.repositoryId()).isEqualTo("acme/widget");
        assertThat(fork.repositoryId()).isEqualTo("jack/widget");
        assertThat(fork.publishRepositoryId()).isEqualTo("jack/widget");
        assertThatThrownBy(() -> ProvisionTarget.derive(
                "unsafe/task", Path.of("/tmp/repo"),
                new TaskAssignment.Direct("acme/widget")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisionTarget.derive(
                "task-1", Path.of("relative/repo"),
                new TaskAssignment.Direct("acme/widget")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void canonicalEngineSnapshotIncludesProviderModelAndValue()
    {
        TaskCreationInput.EngineSnapshot original = new TaskCreationInput.EngineSnapshot(
                "openai", "review-model", "same-value");

        assertThat(original.canonicalValue())
                .isNotEqualTo(new TaskCreationInput.EngineSnapshot(
                        "other", "review-model", "same-value").canonicalValue())
                .isNotEqualTo(new TaskCreationInput.EngineSnapshot(
                        "openai", "other-model", "same-value").canonicalValue())
                .isNotEqualTo(new TaskCreationInput.EngineSnapshot(
                        "openai", "review-model", "other-value").canonicalValue());
    }

    private static TaskCreationInput input(
            TaskAssignment assignment, TaskCreationInput.CreationBase base)
    {
        return new TaskCreationInput(
                "workspace-1", assignment, policy("trunk-1", false, false), base,
                engine(), workModel(), NOW);
    }

    private static TaskAssignment.Identity identity(String suffix)
    {
        return new TaskAssignment.Identity(
                "assignment-" + suffix, "trunk-1", "authorization-" + suffix,
                "user", NOW);
    }

    private static TaskAssignment.PullRequestRef directPr()
    {
        return new TaskAssignment.PullRequestRef(
                new TaskAssignment.Direct("acme/widget"), 42,
                "main", "feature/existing", "base-1", "head-1");
    }

    private static TaskAssignment.ReviewFindingRef finding(
            String sourceReviewId, String findingId)
    {
        return new TaskAssignment.ReviewFindingRef(
                sourceReviewId, findingId, 2, "digest-" + findingId);
    }

    private static TaskCreationInput.TaskPolicy policy(
            String trunkId, boolean autoApprove, boolean autoMerge)
    {
        return new TaskCreationInput.TaskPolicy(
                "policy-1", trunkId, 1, "trunk-default", autoApprove, autoMerge,
                1, 3, 3, true, Optional.empty(), "user", NOW);
    }

    private static TaskCreationInput.EngineSnapshot engine()
    {
        return new TaskCreationInput.EngineSnapshot(
                "openai", "review-model", "engine-v1");
    }

    private static TaskCreationInput.WorkModelSnapshot workModel()
    {
        return new TaskCreationInput.WorkModelSnapshot("work-model-v1");
    }
}
