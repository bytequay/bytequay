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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestContextBuildStore
{
    private static final String WORKSPACE = "workspace";
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final ContextBuildStore store = new ContextBuildStore(settings, tasks, mapper);

    @Test
    void reloadsTheCheckTurnAndWhatItsToolsRecorded()
    {
        ContextBuild.Capture capture = ContextBuild.Capture.empty()
                .withAttempts(ImmutableMap.of("page:https://example.com/spec", "capture", ContextBuildService.CHECKED, "saved"))
                .withGap("Page https://example.com/other could not be read")
                .logged(Instant.EPOCH, "Opened https://example.com/spec#section", ContextBuild.Activity.Tone.DONE);
        ContextBuild saved = store.save(Optional.empty(), build().withCapture(capture));

        assertThat(new ContextBuildStore(settings, tasks, mapper).find(WORKSPACE)).contains(saved);
        assertThat(settings.get(ContextBuildStore.PREFIX + WORKSPACE)).isPresent();
        assertThatThrownBy(() -> store.save(Optional.empty(), build())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        store.delete(WORKSPACE);
        assertThat(store.find(WORKSPACE)).isEmpty();
    }

    @Test
    void aCheckSavedBeforeHarvestsExistedStillReads()
            throws Exception
    {
        ContextBuild saved = store.save(Optional.empty(), build());
        ObjectNode json = (ObjectNode) mapper.readTree(settings.get(ContextBuildStore.PREFIX + WORKSPACE).orElseThrow());
        json.remove("harvest");
        settings.put(ContextBuildStore.PREFIX + WORKSPACE, json.toString());
        assertThat(store.find(WORKSPACE)).contains(saved);
        json.remove("taskId");
        settings.put(ContextBuildStore.PREFIX + WORKSPACE, json.toString());
        assertThatThrownBy(() -> store.find(WORKSPACE)).isInstanceOf(ContextStorageException.class);
    }

    @Test
    void aBuildSavedForAnotherWorkspaceCannotBeRead()
    {
        store.save(Optional.empty(), build());
        settings.put(ContextBuildStore.PREFIX + "other", settings.get(ContextBuildStore.PREFIX + WORKSPACE).orElseThrow());
        assertThatThrownBy(() -> store.find("other")).isInstanceOf(ContextStorageException.class);
    }

    @Test
    void resolvesPurposeOnlyForTheCurrentTaskInItsStoredWorkspace()
    {
        ContextBuild build = store.save(Optional.empty(), build());
        AgentTask task = task(WORKSPACE);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        assertThat(store.findByTask(TASK.toString())).contains(build);
        TaskId other = TaskId.valueOf("22222222-2222-4222-8222-222222222222");
        when(tasks.find(other)).thenReturn(Optional.of(task));
        assertThat(store.findByTask(other.toString())).isEmpty();
        when(tasks.find(TASK)).thenReturn(Optional.of(task("other")));
        assertThat(store.findByTask(TASK.toString())).isEmpty();
    }

    private static AgentTask task(String workspaceId)
    {
        return AgentTask.created(TASK, Optional.of(workspaceId), false, "Check repository", new AgentLaneId("codex"), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/context-check-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    }

    private static ContextBuild build()
    {
        return new ContextBuild(1, "request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(), new RepositoryIdentity("owner", "repository"),
                1, REPOSITORY, SHA, "bytequay/task", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH,
                ContextBuild.Capture.empty(), Optional.of(new ContextBuild.Check("check", "plugin/iceberg/", Optional.empty(), "Brief")), Optional.empty(), Optional.empty());
    }
}
