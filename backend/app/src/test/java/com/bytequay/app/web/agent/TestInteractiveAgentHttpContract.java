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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestInteractiveAgentHttpContract
{
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new Jdk8Module());

    @Test
    void createRequestCannotCarryRendererSelectedPathsOrAttachments()
            throws Exception
    {
        JsonNode request = objectMapper.valueToTree(new InteractiveAgentHttpContract.CreateTaskRequest(
                "workspace-1",
                "Review the pull request",
                "codex",
                new InteractiveAgentHttpContract.TaskDefaults(
                        "gpt-5.6-codex",
                        Optional.of("high"),
                        "AUTO")));

        assertThat(request.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("workspaceId", "title", "laneId", "defaults");
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
                        "laneId",
                        "defaults",
                        "state",
                        "version",
                        "createdAt",
                        "updatedAt",
                        "terminalAt",
                        "terminalReason",
                        "metrics",
                        "summary",
                        "cleanupState",
                        "deleteAfter",
                        "cleanupFailure");
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
                        "notes");
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
                        "claude-opus-4-1",
                        Optional.empty(),
                        "AUTO")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Codex");
    }
}
