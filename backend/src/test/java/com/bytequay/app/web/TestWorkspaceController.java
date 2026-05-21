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
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

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
    private static final String REPO = "octocat/auto-fix-fixture";

    @Autowired
    private WorkspaceController controller;
    @Autowired
    private WorkspaceService workspaces;

    @Test
    void setAutoFixEnabledFlipsTheFlagAndPersists()
    {
        workspaces.addRepo(WorkspaceService.DEFAULT_WORKSPACE_ID, REPO, null);
        // Sanity-check the default: V75 carried the column in with a
        // default of false, addRepo above also passed false explicitly.
        WorkspaceRepo seeded = findRepo(REPO);
        assertThat(seeded.autoFixEnabled()).isFalse();

        WorkspaceRepo enabled = controller.setAutoFixEnabled(
                WorkspaceService.DEFAULT_WORKSPACE_ID, "octocat", "auto-fix-fixture",
                new WorkspaceController.AutoFixEnabledBody(true));
        assertThat(enabled.autoFixEnabled()).isTrue();
        assertThat(findRepo(REPO).autoFixEnabled()).isTrue();

        WorkspaceRepo disabled = controller.setAutoFixEnabled(
                WorkspaceService.DEFAULT_WORKSPACE_ID, "octocat", "auto-fix-fixture",
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
                WorkspaceService.DEFAULT_WORKSPACE_ID, "octocat", "never-attached",
                new WorkspaceController.AutoFixEnabledBody(true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not attached");
    }

    private WorkspaceRepo findRepo(String repoFullName)
    {
        List<WorkspaceRepo> repos = workspaces.listRepos(WorkspaceService.DEFAULT_WORKSPACE_ID);
        return repos.stream()
                .filter(r -> r.repoFullName().equals(repoFullName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("repo not attached: " + repoFullName));
    }
}
