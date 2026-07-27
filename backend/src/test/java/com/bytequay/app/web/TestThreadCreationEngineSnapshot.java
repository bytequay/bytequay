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
package com.bytequay.app.web;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workmodel.WorkModelCatalog;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression coverage for create-time role engine snapshots. */
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class TestThreadCreationEngineSnapshot
{
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkspaceStore workspaces;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private WorkspaceConfigurationService workspaceSettings;
    @Autowired
    private ThreadEngineOverrides threadEngines;
    @Autowired
    private WorkModelResolver workModelResolver;
    @Autowired
    private ThreadService threadService;

    @Test
    void creationFreezesAllRolesAndSurvivesLaterWorkspaceChanges()
            throws Exception
    {
        String workspaceId = "ws-snapshot-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        workspaces.saveWorkspace(new Workspace(
                workspaceId,
                "Snapshot fixture",
                "",
                false,
                null,
                now,
                now));
        workspaceSettings.saveSettings(workspaceId, settings(Map.of(
                "default", "local",
                SessionAudience.DEV, "cli:claude-code")));

        String threadId = null;
        try {
            String response = mvc.perform(post("/api/threads")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "kind", "CLI_AGENT",
                                    "workspaceId", workspaceId,
                                    "title", "Engine snapshot regression",
                                    "engines", Map.of(
                                            SessionAudience.PLAN, "cli:claude-code",
                                            SessionAudience.REVIEW, "cli:claude-code")))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode created = mapper.readTree(response);
            threadId = created.path("id").asText();
            assertThat(threadId).isNotBlank();

            String claudeModel = WorkModelCatalog.agent("claude-code").defaultModel().id();
            WorkModel claude = new WorkModel(
                    WorkModelKind.CLI, "claude-code", claudeModel, null);
            WorkModel local = new WorkModel(
                    WorkModelKind.API, "deepseek", "deepseek-v4-flash", null);

            assertThat(jdbc.queryForObject(
                    "SELECT model FROM threads WHERE id = ?", String.class, threadId))
                    .isEqualTo(claudeModel);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM thread_engines WHERE thread_id = ?",
                    Integer.class,
                    threadId))
                    .isEqualTo(4);
            assertSnapshot(threadId, claude, claude, claude, local);

            workspaceSettings.saveSettings(workspaceId, settings(Map.of(
                    "default", "local",
                    SessionAudience.PLAN, "local",
                    SessionAudience.DEV, "local",
                    SessionAudience.REVIEW, "local",
                    SessionAudience.CI_FIX, "local")));

            assertSnapshot(threadId, claude, claude, claude, local);
            assertThat(workModelResolver.resolveForThread(threadId).choice())
                    .isEqualTo(claude);
        }
        finally {
            if (threadId != null && !threadId.isBlank()) {
                threads.deleteThread(threadId);
            }
            workspaces.deleteWorkspace(workspaceId);
        }
    }

    @Test
    void programmaticCreationAlsoFreezesWorkspaceDefaults()
    {
        String workspaceId = "ws-programmatic-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        workspaces.saveWorkspace(new Workspace(
                workspaceId,
                "Programmatic snapshot fixture",
                "",
                false,
                null,
                now,
                now));
        workspaceSettings.saveSettings(workspaceId, settings(Map.of(
                "default", "local")));

        String threadId = null;
        try {
            Thread created = threadService.create(new ThreadService.NewTaskRequest(
                    ThreadKind.CLI_AGENT,
                    null,
                    null,
                    "Programmatic engine snapshot",
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    workspaceId,
                    null));
            threadId = created.id();

            WorkModel local = new WorkModel(
                    WorkModelKind.API, "deepseek", "deepseek-v4-flash", null);
            assertThat(created.kind()).isEqualTo(ThreadKind.LOGIC_LOOP);
            assertThat(created.provider()).isEqualTo("deepseek");
            assertThat(created.model()).isEqualTo("deepseek-v4-flash");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM thread_engines WHERE thread_id = ?",
                    Integer.class,
                    threadId))
                    .isEqualTo(4);
            assertSnapshot(threadId, local, local, local, local);
        }
        finally {
            if (threadId != null && !threadId.isBlank()) {
                threads.deleteThread(threadId);
            }
            workspaces.deleteWorkspace(workspaceId);
        }
    }

    private void assertSnapshot(
            String threadId,
            WorkModel plan,
            WorkModel dev,
            WorkModel review,
            WorkModel ciFix)
    {
        assertThat(threadEngines.forAudience(threadId, SessionAudience.PLAN)).contains(plan);
        assertThat(threadEngines.forAudience(threadId, SessionAudience.DEV)).contains(dev);
        assertThat(threadEngines.forAudience(threadId, SessionAudience.REVIEW)).contains(review);
        assertThat(threadEngines.forAudience(threadId, SessionAudience.CI_FIX)).contains(ciFix);
    }

    private static WorkspaceSettingsDto settings(Map<String, String> providers)
    {
        WorkspaceSettingsDto defaults = WorkspaceSettingsDto.defaults();
        return new WorkspaceSettingsDto(
                defaults.sessionCapUsd(),
                defaults.dailyCapUsd(),
                defaults.pauseAtCap(),
                defaults.syncSeconds(),
                defaults.brainBudgetChars(),
                defaults.distillMinutes(),
                List.copyOf(SessionAudience.ALL),
                providers,
                defaults.notifyCi(),
                defaults.notifyCompletions(),
                defaults.qualityScanEnabled(),
                defaults.remoteIssueIntakeEnabled());
    }
}
