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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLegacyBrainReviewRetirement
{
    private final TaskStore tasks = mock(TaskStore.class);
    private final PRService prs = mock(PRService.class);
    private final BrainReviewServiceImpl service = new BrainReviewServiceImpl(tasks, prs);

    @Test
    void legacyMutationSurfaceFailsClosed()
    {
        PR pr = PR.create(
                "pr-1", "task-1", "feature/x", "main", "Title", "", Instant.EPOCH);
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-1");
        when(prs.findById("pr-1")).thenReturn(Optional.of(pr));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(tasks.isV2Task("task-1")).thenReturn(false);

        assertRetired(() -> service.reviewBeforeLocalOpen("pr-1", "agent"));
        assertRetired(() -> service.pauseActiveReview("task-1", "pause"));
        assertRetired(() -> service.resumeParkedReview("task-1"));
        assertRetired(() -> service.reviewBeforeRoundGate(null, null));
        assertRetired(() -> service.recordVerdict(
                "task-1", "stage-1", "run-1", "round", "approved"));
        assertThat(service.ownsParkedResume("task-1")).isFalse();
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
