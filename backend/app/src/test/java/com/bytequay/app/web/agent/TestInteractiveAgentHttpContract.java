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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void createRequestCannotCarryRendererSelectedPathsOrAttachments()
            throws Exception
    {
        JsonNode request = objectMapper.valueToTree(new InteractiveAgentHttpContract.CreateTaskRequest(
                "workspace-1",
                "Review the pull request",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("gpt-5.6-codex"),
                        Optional.of("high"),
                        "AUTO")));

        assertThat(request.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("workspaceId", "message", "laneId", "defaults");
        assertThat(request.toString())
                .doesNotContain("worktree")
                .doesNotContain("repository")
                .doesNotContain("path")
                .doesNotContain("attachment");
    }

    @Test
    void narrowedResponsesContainNoBackendExecutionHandles()
    {
        assertThat(InteractiveAgentHttpContract.TaskResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "id",
                        "workspaceId",
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
                        "checkoutRemovedAt");
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
                        "settings",
                        "terminalReason",
                        "metrics");
        assertThat(InteractiveAgentHttpContract.TaskSummaryResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "finalResponse",
                        "resultBranch",
                        "resultHead",
                        "tests",
                        "metrics",
                        "learnings",
                        "notes",
                        "children");
        assertThat(InteractiveAgentHttpContract.TurnSettings.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "model",
                        "effort",
                        "permissionPolicy",
                        "sandboxPolicy",
                        "maximumRuntimeMillis",
                        "attachments");
        assertThat(InteractiveAgentHttpContract.AttachmentReference.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "name", "mediaType");
    }

    @Test
    void keepsInteractiveCreationCodexOnlyUntilClaudeParityExists()
    {
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.CreateTaskRequest(
                "workspace-1",
                "Task",
                "claude",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("claude-opus-4-1"),
                        Optional.empty(),
                        "AUTO")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Codex");
    }

    @Test
    void rejectsMissingModelAndInternalPermissionPolicies()
    {
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.CreateTaskRequest(
                "workspace-1",
                "Task",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.empty(),
                        Optional.empty(),
                        "AUTO")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> new InteractiveAgentHttpContract.CreateTaskRequest(
                "workspace-1",
                "Task",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        Optional.of("gpt-5.6-codex"),
                        Optional.empty(),
                        "CI_AUTOFIX")))
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
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TurnMetrics.empty());

        JsonNode taskJson = objectMapper.valueToTree(InteractiveAgentHttpContract.task(task));
        JsonNode turnJson = objectMapper.valueToTree(InteractiveAgentHttpContract.turn(turn));

        assertThat(taskJson.path("defaults").get("model").isNull()).isTrue();
        assertThat(taskJson.toString()).doesNotContain("worktree", "repository", "sessionReference");
        assertThat(turnJson.get("settings").isNull()).isTrue();
        assertThat(turnJson.toString()).doesNotContain("private-idempotency-key", "providerTurnReference");
    }
}
