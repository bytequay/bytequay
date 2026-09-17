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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestInteractiveAgentHttpContract
{
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule());

    @TempDir
    private Path temporaryDirectory;

    @Test
    void projectsPlanAbsenceAndExplicitClearSeparately()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), TaskId.random(), "plan",
                new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of()), Instant.EPOCH);
        assertThat(objectMapper.valueToTree(InteractiveAgentHttpContract.turn(turn)).get("plan").isNull()).isTrue();
        AgentTurn planned = turn.withPlan(ImmutableList.of(new ProviderAgentEvent.PlanItem("Verify", ProviderAgentEvent.PlanItemState.IN_PROGRESS)));
        JsonNode plan = objectMapper.valueToTree(InteractiveAgentHttpContract.turn(planned)).path("plan");
        assertThat(plan.get(0).path("text").asText()).isEqualTo("Verify");
        assertThat(plan.get(0).path("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(objectMapper.valueToTree(InteractiveAgentHttpContract.turn(planned.withPlan(ImmutableList.of()))).path("plan").isEmpty()).isTrue();
    }

    @Test
    void createRequestCannotCarryRendererSelectedPathsOrAttachments()
            throws Exception
    {
        JsonNode request = objectMapper.valueToTree(new InteractiveAgentHttpContract.CreateTaskRequest(
                "11111111-1111-1111-1111-111111111111",
                "workspace-1",
                "Review the pull request",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("gpt-5.6-codex"),
                        Optional.of("high"),
                        "AUTO"),
                "create-key"));

        assertThat(request.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("taskId", "workspaceId", "message", "laneId", "defaults", "idempotencyKey", "developmentAutomation", "issueSource");
        assertThat(request.toString())
                .doesNotContain("worktree")
                .doesNotContain("repository")
                .doesNotContain("path")
                .doesNotContain("attachment");
    }

    @Test
    void exposesStructuredIssueRequestProvenanceWithoutAWrappedPrompt()
    {
        GitHubIssueSource source = new GitHubIssueSource("github.com", 70, "owner/repo", 7, "https://github.com/owner/repo/issues/7",
                "Fix selection", "Accepted Markdown description", Instant.parse("2026-09-16T00:00:00Z"));
        JsonNode json = objectMapper.valueToTree(new InteractiveAgentHttpContract.IssueSourceResponse(source,
                Optional.of("initial-turn"), "Implement using the existing model"));
        assertThat(json.path("title").asText()).isEqualTo("Fix selection");
        assertThat(json.path("initialTurnId").asText()).isEqualTo("initial-turn");
        assertThat(json.path("instructions").asText()).isEqualTo("Implement using the existing model");
        assertThat(json.has("source")).isFalse();
    }

    @Test
    void narrowedResponsesContainNoBackendExecutionHandles()
    {
        assertThat(InteractiveAgentHttpContract.TaskResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "id",
                        "workspaceId",
                        "generatedBranch",
                        "title",
                        "titleSource",
                        "laneId",
                        "defaults",
                        "state",
                        "mutationClaim",
                        "version",
                        "createdAt",
                        "updatedAt",
                        "terminalAt",
                        "terminalReason",
                        "metrics",
                        "summary",
                        "cleanupState",
                        "deleteAfter",
                        "cleanupFailure",
                        "checkoutRemovedAt",
                        "codegraph",
                        "archivedAt",
                        "interactive",
                        "developmentContinuationUnfinished",
                        "developmentCi",
                        "pullRequest",
                        "localReviewHead",
                        "issueSource");
        assertThat(InteractiveAgentHttpContract.MutationClaim.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("kind", "ownerId", "acquiredAt");
        assertThat(InteractiveAgentHttpContract.TurnResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "id",
                        "taskId",
                        "state",
                        "version",
                        "createdAt",
                        "startedAt",
                        "endedAt",
                        "cancellationRequestedAt",
                        "delivered",
                        "knownUnsent",
                        "settings",
                        "terminalReason",
                        "failureKind",
                        "metrics",
                        "developmentResult",
                        "developmentOrigin",
                        "plan");
        assertThat(InteractiveAgentHttpContract.TaskSummaryResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "finalResponse",
                        "resultBranch",
                        "resultHead",
                        "metrics",
                        "notes",
                        "children");
        assertThat(InteractiveAgentHttpContract.TurnSettings.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "model",
                        "effort",
                        "permissionPolicy",
                        "maximumRuntimeMillis",
                        "attachments",
                        "serviceTier",
                        "developmentAutomation");
        assertThat(InteractiveAgentHttpContract.AttachmentReference.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "name", "mediaType");
    }

    @Test
    void acceptsConfiguredInteractiveLanes()
    {
        InteractiveAgentHttpContract.CreateTaskRequest request = new InteractiveAgentHttpContract.CreateTaskRequest(
                "11111111-1111-1111-1111-111111111111",
                "workspace-1",
                "Task",
                "claude",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("claude-opus-4-1"),
                        Optional.empty(),
                        "AUTO"),
                "create-key");

        assertThat(request.laneId()).isEqualTo("claude");
    }

    @Test
    void rejectsMissingModelAndInternalPermissionPolicies()
    {
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.CreateTaskRequest(
                "11111111-1111-1111-1111-111111111111",
                "workspace-1",
                "Task",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.empty(),
                        Optional.empty(),
                        "AUTO"),
                "create-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.CreateTaskRequest(
                "11111111-1111-1111-1111-111111111111",
                "workspace-1",
                "Task",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("gpt-5.6-codex"),
                        Optional.empty(),
                        "CI_AUTOFIX"),
                "create-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
    }

    @Test
    void guardsDefaultsUpdatesWithAVersionAndTheSamePermissionRule()
    {
        InteractiveAgentHttpContract.TaskDefaults defaults = new InteractiveAgentHttpContract.TaskDefaults(
                Optional.of("gpt-5.6-codex"),
                Optional.of("high"),
                "AUTO");
        assertThat(new InteractiveAgentHttpContract.UpdateDefaultsRequest(3, defaults).expectedVersion())
                .isEqualTo(3);

        // Without a version a stale screen could overwrite a change it never saw.
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.UpdateDefaultsRequest(-1, defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");

        // An update must not be a way around the rule creation enforces.
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.UpdateDefaultsRequest(
                0,
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("gpt-5.6-codex"),
                        Optional.empty(),
                        "CI_AUTOFIX")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
    }

    @Test
    void mapsLegacyMissingModelAndTurnSettingsWithoutLeakingHandles()
    {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                taskId,
                Optional.of("workspace-1"),
                true,
                "Legacy task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "repository"),
                temporaryDirectory.toAbsolutePath(),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                now);
        AgentTurn turn = new AgentTurn(
                TurnId.random(),
                taskId,
                "private-idempotency-key",
                TurnState.ACCEPTED,
                0,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AgentMetrics.empty());

        JsonNode taskJson = objectMapper.valueToTree(InteractiveAgentHttpContract.task(task, task.metrics(), Optional.empty(), Optional.empty(), Optional.empty()));
        JsonNode turnJson = objectMapper.valueToTree(InteractiveAgentHttpContract.turn(turn));

        assertThat(taskJson.path("defaults").get("model").isNull()).isTrue();
        assertThat(taskJson.get("generatedBranch").isNull()).isTrue();
        assertThat(taskJson.toString()).doesNotContain("worktree", "repository", "sessionReference");
        assertThat(turnJson.get("settings").isNull()).isTrue();
        assertThat(turnJson.toString()).doesNotContain("private-idempotency-key", "providerTurnReference");
    }

    @Test
    void exposesTypedTurnFailureWithoutProviderHandles()
    {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        AgentTurn turn = AgentTurn.accepted(
                        TurnId.random(),
                        TaskId.random(),
                        "private-key",
                        new com.bytequay.agent.core.AgentTurnSettings(
                                Optional.of("opus"),
                                Optional.of("High"),
                                AgentPermissionPolicy.AUTO,
                                java.time.Duration.ofMinutes(5),
                                java.util.List.of()),
                        now)
                .markDeliveryStarted(now.plusSeconds(1))
                .running(new ProviderTurnRef("private-provider-turn"), Optional.of("opus"))
                .finish(
                        TurnState.FAILED,
                        AgentMetrics.empty(),
                        Optional.of("Claude sign-in required"),
                        Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED),
                        now.plusSeconds(2));

        JsonNode json = objectMapper.valueToTree(InteractiveAgentHttpContract.turn(turn));

        assertThat(json.required("failureKind").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(json.toString()).doesNotContain("private-key", "private-provider-turn");
    }

    @Test
    void distinguishesSavedDefaultsFromOneSubmissionAndKeepsTheDurableResultWireShape()
            throws Exception
    {
        InteractiveAgentHttpContract.CreateTaskRequest request = objectMapper.readValue("""
                {"taskId":"11111111-1111-1111-1111-111111111111","workspaceId":"workspace-1",
                "message":"Task","laneId":"codex","idempotencyKey":"create-key",
                "defaults":{"model":"model-a","permissionPolicy":"AUTO","developmentAutomation":
                {"autoDraftPr":true,"autoCiFix":true,"ciLaneId":"claude"}},
                "developmentAutomation":{"autoDraftPr":false,"autoCiFix":false,"ciLaneId":null}}
                """, InteractiveAgentHttpContract.CreateTaskRequest.class);

        assertThat(request.defaults().developmentAutomation().orElseThrow().toDomain())
                .isEqualTo(new DevelopmentAutomation(true, true, true, Optional.of(new AgentLaneId("claude"))));
        assertThat(request.developmentAutomation().orElseThrow().toDomain()).isEqualTo(DevelopmentAutomation.LOCAL_ONLY.forDevelopment());

        AgentTurn turn = AgentTurn.accepted(TurnId.random(), TaskId.random(), "private-key",
                        new AgentTurnSettings(Optional.of("model-a"), Optional.empty(), AgentPermissionPolicy.AUTO,
                                Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.of(DevelopmentAutomation.LOCAL_ONLY)),
                        Instant.parse("2026-09-01T00:00:00Z"))
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, TurnId.random()))
                .withDevelopmentResult(new DevelopmentResultReport(false, Optional.of("Choose the expected behavior.")));
        JsonNode json = objectMapper.valueToTree(InteractiveAgentHttpContract.turn(turn));

        assertThat(json.path("settings").path("developmentAutomation").has("autoCommit")).isFalse();
        assertThat(json.path("developmentResult").path("readyForDelivery").asBoolean()).isFalse();
        assertThat(json.path("developmentResult").path("message").asText()).isEqualTo("Choose the expected behavior.");
        assertThat(json.path("developmentResult").size()).isEqualTo(3);
        assertThat(json.path("developmentResult").path("learningSummary").isNull()).isTrue();
        assertThat(json.path("developmentOrigin").path("kind").asText()).isEqualTo("AUTO_COMMIT");
        assertThat(json.path("developmentOrigin").path("parentUserTurnId").asText()).isEqualTo(turn.developmentOrigin().orElseThrow().parentUserTurnId().toString());
    }

    @Test
    void namesTheOpenPullRequestElseTheMergedOneAndNeverAClosedOne()
    {
        PullRequest merged = pullRequest(40, "merged", false);
        PullRequest olderOpen = pullRequest(41, "open", false);
        PullRequest draft = pullRequest(43, "open", true);
        PullRequest closed = pullRequest(44, "closed", false);

        assertThat(InteractiveAgentHttpContract.taskPullRequest(ImmutableList.of(merged, olderOpen, draft, closed)))
                .contains(new InteractiveAgentHttpContract.TaskPullRequestResponse(43, "Change 43", "open", true));
        assertThat(InteractiveAgentHttpContract.taskPullRequest(ImmutableList.of(closed, merged)))
                .contains(new InteractiveAgentHttpContract.TaskPullRequestResponse(40, "Change 40", "merged", false));
        assertThat(InteractiveAgentHttpContract.taskPullRequest(ImmutableList.of(closed))).isEmpty();
    }

    private static PullRequest pullRequest(int number, String state, boolean draft)
    {
        return new PullRequest(number, "owner/repo", number, "Change " + number, "owner", "https://github.com/owner/repo/pull/" + number, Instant.EPOCH, Instant.EPOCH,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), draft, null, null, null, ImmutableList.of(), null, 1, 1, 0,
                null, state, null, null, null, null, Instant.EPOCH, "owner/branch", null, null, null, null);
    }
}
