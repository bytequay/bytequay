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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * Enforces the task ↔ PR linking rules server-side:
 * <ul>
 *   <li>Authorship gates the affordance: you may open a <em>dev task</em>
 *       only on your own PR; you may <em>review</em> only someone else's.</li>
 *   <li>Task ↔ PR is 1:1 active: a PR has at most one non-COMPLETED task.</li>
 * </ul>
 * The UI mirrors these, but the server is the source of truth.
 */
@Service
public class PrTaskLinkService
{
    private final PullRequestService pullRequests;
    private final TaskStore taskStore;
    private final AppSettingsStore appSettings;

    public PrTaskLinkService(
            PullRequestService pullRequests,
            TaskStore taskStore,
            AppSettingsStore appSettings)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    /** The connected GitHub user's login, or "" when not connected. */
    public String viewerLogin()
    {
        return appSettings.get(AppSettingsStore.Key.GITHUB_LOGIN).orElse("");
    }

    /**
     * Guard the "Assign review" entry: a review may only target someone
     * else's PR. Reviewing your own PR is the dev-task lifecycle's job.
     */
    public void assertCanReview(String repoFullName, int prNumber)
    {
        if (isViewer(authorOf(repoFullName, prNumber))) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "cannot_review_own_pr · use the dev task lifecycle");
        }
    }

    /**
     * Guard the "Create dev task" entry: only on your own PR, and only
     * when no active task already owns it. Returns the {@code owner/repo#n}
     * ref the caller links onto the new task.
     */
    public String assertCanCreateDevTask(String repoFullName, int prNumber)
    {
        if (!isViewer(authorOf(repoFullName, prNumber))) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "cannot_create_task_for_others_pr");
        }
        String prRef = repoFullName + "#" + prNumber;
        taskStore.findActiveTaskByPrRef(prRef).ifPresent(active -> {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "pr already has an active task: active_task_id=" + active.id());
        });
        return prRef;
    }

    private String authorOf(String repoFullName, int prNumber)
    {
        PullRequest pr = pullRequests.lookupPullRequest(repoFullName, prNumber);
        return pr == null ? "" : pr.author();
    }

    private boolean isViewer(String login)
    {
        String viewer = viewerLogin();
        return login != null && !login.isBlank()
                && !viewer.isBlank() && login.equalsIgnoreCase(viewer);
    }
}
