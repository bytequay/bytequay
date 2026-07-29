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

import com.bytequay.app.developmentflow.execution.remote.SqliteReviewBuildCommentStore.ProposalView;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewPassPublicationStore.PublicationView;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.review.ReviewBuildSpawnService;
import com.bytequay.app.service.review.ReviewPassService;
import com.bytequay.app.service.review.ScheduledReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewBuildCommentController
{
    @Test
    void routesReadApproveAndDiscardOnlyThroughTheV2Runtime()
    {
        V2UserRemoteActionRuntime runtime = mock(
                V2UserRemoteActionRuntime.class);
        ReviewController controller = new ReviewController(
                mock(ReviewPassService.class),
                mock(ScheduledReviewService.class),
                mock(AppSettingsStore.class),
                mock(ReviewBuildSpawnService.class), runtime);
        ProposalView pending = proposal("PENDING", null);
        ProposalView approved = proposal("APPROVED", "APPROVE");
        ProposalView discarded = proposal("DISCARDED", "DISCARD");
        when(runtime.findReviewBuildCommentProposal("pass-1"))
                .thenReturn(Optional.of(pending));
        when(runtime.approveReviewBuildComments("pass-1", "approve-key"))
                .thenReturn(approved);
        when(runtime.discardReviewBuildComments("pass-2", "discard-key"))
                .thenReturn(discarded);

        assertThat(controller.reviewBuildComments("pass-1").getBody())
                .isEqualTo(pending);
        assertThat(controller.approveReviewBuildComments(
                "pass-1", " approve-key ")).isEqualTo(approved);
        assertThat(controller.discardReviewBuildComments(
                "pass-2", "discard-key")).isEqualTo(discarded);

        verify(runtime).approveReviewBuildComments("pass-1", "approve-key");
        verify(runtime).discardReviewBuildComments("pass-2", "discard-key");
    }

    @Test
    void rejectsABlankIdempotencyKeyBeforeAuthorization()
    {
        V2UserRemoteActionRuntime runtime = mock(
                V2UserRemoteActionRuntime.class);
        ReviewController controller = new ReviewController(
                mock(ReviewPassService.class),
                mock(ScheduledReviewService.class),
                mock(AppSettingsStore.class),
                mock(ReviewBuildSpawnService.class), runtime);

        assertThatThrownBy(() -> controller.approveReviewBuildComments(
                "pass-1", "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
    }

    @Test
    void routesStandalonePublishAndDurableReadThroughTheV2Runtime()
    {
        V2UserRemoteActionRuntime runtime = mock(
                V2UserRemoteActionRuntime.class);
        ReviewController controller = new ReviewController(
                mock(ReviewPassService.class),
                mock(ScheduledReviewService.class),
                mock(AppSettingsStore.class),
                mock(ReviewBuildSpawnService.class), runtime);
        PublicationView queued = publication("QUEUED", false);
        when(runtime.publishReviewPass(
                "pass-1", "publish-key", "request_changes", List.of("f-1")))
                .thenReturn(queued);
        when(runtime.findReviewPassPublication("pass-1"))
                .thenReturn(Optional.of(queued));

        assertThat(controller.publish(
                "pass-1", " publish-key ",
                new ReviewController.PublishReviewRequest(
                        "REQUEST_CHANGES", List.of("f-1"))))
                .isEqualTo(queued);
        assertThat(controller.publication("pass-1").getBody())
                .isEqualTo(queued);

        verify(runtime).publishReviewPass(
                "pass-1", "publish-key", "request_changes", List.of("f-1"));
        verify(runtime).findReviewPassPublication("pass-1");
    }

    @Test
    void standalonePublishRequiresAStableCommandKey()
    {
        ReviewController controller = new ReviewController(
                mock(ReviewPassService.class),
                mock(ScheduledReviewService.class),
                mock(AppSettingsStore.class),
                mock(ReviewBuildSpawnService.class),
                mock(V2UserRemoteActionRuntime.class));

        assertThatThrownBy(() -> controller.publish(
                "pass-1", " ",
                new ReviewController.PublishReviewRequest(
                        "COMMENT", List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
    }

    private static ProposalView proposal(String status, String decision)
    {
        return new ProposalView(
                "trunk-1", "pass-1", "acme/widget", 17, "head-1",
                "digest", status, decision,
                decision == null ? null : "command", null, null, null,
                null,
                List.of());
    }

    private static PublicationView publication(String status, boolean terminal)
    {
        return new PublicationView(
                "pass-1", "publish-key", status, terminal, "COMMENT",
                List.of("f-1"), null, null, null);
    }
}
