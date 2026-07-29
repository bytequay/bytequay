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
package com.bytequay.app.service.stage;

import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestLegacyStageBudgetRetirement
{
    private final StageStore stages = mock(StageStore.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final StageBudgetService service = new StageBudgetService(
            stages, tasks, notifications, new ObjectMapper());

    @Test
    void everyLegacyMutationRejectsBeforeStageTaskOrNotificationWrite()
    {
        assertRetired(() -> service.onStageOpened(null));
        assertRetired(() -> service.onAutoPush(null));
        assertRetired(() -> service.extendBudget(UUID.randomUUID(), 5));
        assertRetired(() -> service.fallbackToReview(UUID.randomUUID()));

        verifyNoInteractions(stages, tasks, notifications);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
