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

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.review.ReviewPassTerminatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the terminate listener: it reads the pass's reviewer seats and
 * finding tally and records them on the single CLOSED event when it closes
 * the linked review stage.
 */
class TestReviewStageCloser
{
    private static final Instant NOW = Instant.parse("2026-06-21T09:00:00Z");

    private StageStateMachine stageMachine;
    private ReviewStore reviewStore;
    private ReviewStageCloser closer;

    @BeforeEach
    void setUp()
    {
        stageMachine = mock(StageStateMachine.class);
        reviewStore = mock(ReviewStore.class);
        closer = new ReviewStageCloser(stageMachine, reviewStore);
    }

    @Test
    void closesTheStageWithThePanelSummary()
    {
        UUID stageId = UUID.randomUUID();
        when(reviewStore.listParticipantsForPass("pass-9")).thenReturn(List.of(
                participant(ReviewParticipantKind.LEAD, "Moderator"),
                participant(ReviewParticipantKind.REVIEWER, "Claude"),
                participant(ReviewParticipantKind.REVIEWER, "GPT-5")));
        when(reviewStore.listFindingsForPass("pass-9")).thenReturn(List.of(
                finding(ReviewFindingStatus.AGREED),
                finding(ReviewFindingStatus.AGREED),
                finding(ReviewFindingStatus.REPORTED)));

        closer.onReviewPassTerminated(new ReviewPassTerminatedEvent("pass-9", stageId.toString()));

        ArgumentCaptor<Map<String, Object>> payload = captor();
        verify(stageMachine).close(eq(stageId), eq("review_pass_terminated"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("seatNames", List.of("Claude", "GPT-5"))
                .containsEntry("findingCount", 3)
                .containsEntry("agreedCount", 2);
    }

    @Test
    void ignoresAnUnparseableStageId()
    {
        closer.onReviewPassTerminated(new ReviewPassTerminatedEvent("pass-9", "not-a-uuid"));
        verify(stageMachine, never()).close(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor()
    {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static ReviewParticipant participant(ReviewParticipantKind kind, String label)
    {
        return new ReviewParticipant(
                UUID.randomUUID().toString(), "pass-9", kind, null, label, null, null, NOW);
    }

    private static ReviewFinding finding(ReviewFindingStatus status)
    {
        return new ReviewFinding(
                UUID.randomUUID().toString(), "pass-9", "src/foo.ts", 1,
                ReviewFindingSeverity.MAJOR, status, "body", null, null, NOW);
    }
}
