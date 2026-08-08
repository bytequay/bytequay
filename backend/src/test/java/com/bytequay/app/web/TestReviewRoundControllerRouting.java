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

import com.bytequay.app.developmentflow.stage.V2RemoteFeedbackControlService;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.review.ReviewRoundServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewRoundControllerRouting
{
    @Test
    void preservesLegacyReadsButRoutesOnlyV2ApprovalMutations()
    {
        ReviewRoundServiceImpl legacy = mock(ReviewRoundServiceImpl.class);
        V2RemoteFeedbackControlService v2 =
                mock(V2RemoteFeedbackControlService.class);
        TaskStore tasks = mock(TaskStore.class);
        ReviewRoundController controller =
                new ReviewRoundController(legacy, v2, tasks);
        ReviewRound legacyRound = round("legacy-round", "legacy-task");
        ReviewRound v2Round = round("v2-batch", "v2-task");
        when(tasks.findWorkflowVersion("legacy-task"))
                .thenReturn(Optional.of("LEGACY"));
        when(tasks.findWorkflowVersion("v2-task"))
                .thenReturn(Optional.of("V2"));
        when(legacy.findByTask("legacy-task")).thenReturn(List.of(legacyRound));
        when(v2.findByTask("v2-task")).thenReturn(List.of(v2Round));
        when(v2.findTaskId("legacy-round")).thenReturn(Optional.empty());
        when(v2.findTaskId("v2-batch")).thenReturn(Optional.of("v2-task"));
        when(legacy.findById("legacy-round")).thenReturn(Optional.of(legacyRound));
        when(v2.approve("v2-batch")).thenReturn(v2Round);

        assertThat(controller.roundsForTask("legacy-task"))
                .containsExactly(legacyRound);
        assertThat(controller.roundsForTask("v2-task"))
                .containsExactly(v2Round);
        assertThatThrownBy(() -> controller.approve("legacy-round"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThat(controller.approve("v2-batch")).isEqualTo(v2Round);

        verify(legacy).findByTask("legacy-task");
        verify(v2).findByTask("v2-task");
        verify(legacy).findById("legacy-round");
        verify(legacy, never()).approve("legacy-round");
        verify(v2).approve("v2-batch");
        verify(legacy, never()).findByTask("v2-task");
        verify(legacy, never()).approve("v2-batch");
    }

    private static ReviewRound round(String id, String taskId)
    {
        return new ReviewRound(
                id, taskId, 1, List.of("@reviewer"),
                ReviewRoundState.AWAITING_GATE,
                ReviewRound.ReviewRoundStats.empty(), null,
                Instant.EPOCH, Instant.EPOCH, null,
                ReviewRound.ORIGIN_EXTERNAL, null, 1, 5);
    }
}
