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

import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of the auto-fix opt-in endpoint. The endpoint
 * is the only way the user can flip the per-repo flag (no UI yet,
 * but the data plane is wired through Phase 2 / 7 and the automation
 * coordinator already reads the column).
 */
@SpringBootTest
class TestWorkspaceController
{
    private static final String WORKSPACE_ID = "ws-default";
    private static final String REPO = "octocat/auto-fix-fixture";

    @Autowired
    private WorkspaceController controller;
    @Autowired
    private WorkspaceRepositoryController repositoryController;
    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private WorkspaceStore workspaceStore;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void setAutoFixEnabledFlipsTheFlagAndPersists()
    {
        // Repository binding is now established only by workspace creation;
        // seed the aggregate directly instead of exercising the retired
        // multi-repo attachment path.
        workspaceStore.addRepo(new WorkspaceRepo(
                WORKSPACE_ID, REPO, null, false, Instant.now()));
        // Sanity-check the default: V75 carried the column in with a
        // default of false, addRepo above also passed false explicitly.
        WorkspaceRepo seeded = findRepo(REPO);
        assertThat(seeded.autoFixEnabled()).isFalse();

        WorkspaceRepo enabled = controller.setAutoFixEnabled(
                WORKSPACE_ID, "octocat", "auto-fix-fixture",
                new WorkspaceController.AutoFixEnabledBody(true));
        assertThat(enabled.autoFixEnabled()).isTrue();
        assertThat(findRepo(REPO).autoFixEnabled()).isTrue();

        WorkspaceRepo disabled = controller.setAutoFixEnabled(
                WORKSPACE_ID, "octocat", "auto-fix-fixture",
                new WorkspaceController.AutoFixEnabledBody(false));
        assertThat(disabled.autoFixEnabled()).isFalse();
        assertThat(findRepo(REPO).autoFixEnabled()).isFalse();
    }

    @Test
    void setAutoFixEnabledOnUnattachedRepoReturns404()
    {
        // Even if the workspace exists, flipping the flag against a
        // repo that was never attached must fail loudly — silently
        // creating the row would let a typo enable auto-fix on the
        // wrong slug.
        assertThatThrownBy(() -> controller.setAutoFixEnabled(
                WORKSPACE_ID, "octocat", "never-attached",
                new WorkspaceController.AutoFixEnabledBody(true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not attached");
    }

    @Test
    void missingUpstreamRelationReturnsNoContent()
    {
        assertThat(repositoryController.relation(WORKSPACE_ID).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repositoryController.relation(WORKSPACE_ID).getBody()).isNull();
    }

    @Test
    void discoversACompletedUpstreamCherryPickAfterReload()
    {
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    created_at_ms, updated_at_ms)
                VALUES ('reload-job', ?, ?, 'COMPLETED', 'main', 'source-sha',
                    'main', 'base-sha', 'release-pick',
                    '[{"sha":"commit-1","upstreamPr":"acme/upstream#1","subject":"Feature"}]',
                    '["commit-1"]', '[]', 1, '[]', '/tmp/reload-job',
                    0, 0, 5000, 100, 100)
                """, WORKSPACE_ID, WORKSPACE_ID);

        assertThat(repositoryController.upstreamCherryPicks(WORKSPACE_ID, 20))
                .extracting(job -> job.jobId())
                .contains("reload-job");
    }

    @Test
    void retryEndpointRejectsANonFailedUpstreamCherryPick()
    {
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    created_at_ms, updated_at_ms)
                VALUES ('retry-completed-job', ?, ?, 'COMPLETED',
                    'main', 'origin/main', 'main', 'base-sha', 'release-pick',
                    '[]', '[]', '[]', 0, '[]', '/tmp/retry-completed-job',
                    0, 0, 5000, 200, 200)
                """, WORKSPACE_ID, WORKSPACE_ID);

        assertThatThrownBy(() -> repositoryController.retryUpstreamCherryPick(
                WORKSPACE_ID, "retry-completed-job"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private WorkspaceRepo findRepo(String repoFullName)
    {
        List<WorkspaceRepo> repos = workspaces.listRepos(WORKSPACE_ID);
        return repos.stream()
                .filter(r -> r.repoFullName().equals(repoFullName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("repo not attached: " + repoFullName));
    }
}
