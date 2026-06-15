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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPrTaskLinkService
{
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final AppSettingsStore appSettings = mock(AppSettingsStore.class);
    private final PrTaskLinkService service =
            new PrTaskLinkService(pullRequests, taskStore, appSettings);

    @Test
    void cannotReviewYourOwnPr()
    {
        viewerIs("alice");
        when(pullRequests.lookupPullRequest("acme/widget", 42)).thenReturn(prBy("alice"));

        assertThatThrownBy(() -> service.assertCanReview("acme/widget", 42))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot_review_own_pr");
    }

    @Test
    void canReviewSomeoneElsesPr()
    {
        viewerIs("alice");
        when(pullRequests.lookupPullRequest("acme/widget", 42)).thenReturn(prBy("bob"));

        assertThatCode(() -> service.assertCanReview("acme/widget", 42)).doesNotThrowAnyException();
    }

    @Test
    void cannotCreateADevTaskForSomeoneElsesPr()
    {
        viewerIs("alice");
        when(pullRequests.lookupPullRequest("acme/widget", 42)).thenReturn(prBy("bob"));

        assertThatThrownBy(() -> service.assertCanCreateDevTask("acme/widget", 42))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot_create_task_for_others_pr");
    }

    @Test
    void cannotCreateASecondActiveTaskForTheSamePr()
    {
        viewerIs("alice");
        when(pullRequests.lookupPullRequest("acme/widget", 42)).thenReturn(prBy("alice"));
        when(taskStore.findActiveTaskByPrRef("acme/widget#42"))
                .thenReturn(Optional.of(taskWithId("task-existing")));

        assertThatThrownBy(() -> service.assertCanCreateDevTask("acme/widget", 42))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("task-existing");
    }

    @Test
    void allowsCreatingADevTaskOnYourOwnUnlinkedPr()
    {
        viewerIs("alice");
        when(pullRequests.lookupPullRequest("acme/widget", 42)).thenReturn(prBy("alice"));
        when(taskStore.findActiveTaskByPrRef("acme/widget#42")).thenReturn(Optional.empty());

        assertThat(service.assertCanCreateDevTask("acme/widget", 42)).isEqualTo("acme/widget#42");
    }

    private void viewerIs(String login)
    {
        when(appSettings.get(AppSettingsStore.Key.GITHUB_LOGIN)).thenReturn(Optional.of(login));
    }

    private static Task taskWithId(String id)
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new Task(
                id, "thread-1", 1L, TaskStatus.RUNNING,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null);
    }

    private static PullRequest prBy(String author)
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new PullRequest(
                1L, "acme/widget", 42, "Test PR", author,
                "https://github.com/acme/widget/pull/42",
                now, now, PullRequest.Origin.AUTHORED,
                List.of(), Map.of(), false,
                null, null, null, List.of(), null,
                0, 0, 0, null,
                "open", null, null, null, null, null,
                Map.of(), null, null, "dev/x");
    }
}
