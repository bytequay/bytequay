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

import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewTurnPrompt;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Admission;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Seat;
import com.bytequay.app.service.review.ReviewProviderEndpoints.AgentLaunch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewAssignmentTurnBudget
{
    @Test
    void dividesTheRoundCapAcrossConcurrentPrimarySeats()
    {
        ReviewAssignmentTurnRuntime.Store store =
                mock(ReviewAssignmentTurnRuntime.Store.class);
        ReviewProviderEndpoints providers = mock(ReviewProviderEndpoints.class);
        when(providers.freeze(any())).thenReturn(new AgentLaunch(
                AgentTurnProviderSession.Transport.API,
                "openai", "account-1", "gpt-5.6"));
        AtomicInteger ids = new AtomicInteger();
        ReviewAssignmentTurnRuntime runtime = new ReviewAssignmentTurnRuntime(
                store, providers,
                mock(ReviewAssignmentTurnRuntime.TicketControl.class),
                new ObjectMapper(), Clock.systemUTC(), 53123,
                () -> Integer.toString(ids.incrementAndGet()));
        ProviderChoice provider = new ProviderChoice("openai", "api", "openai");
        ReviewTurnPrompt prompt = new ReviewTurnPrompt("system", "review");

        runtime.admit("round-1", "head-1", List.of(
                new Seat("assignment-1", provider, Path.of("/tmp/review"), prompt),
                new Seat("assignment-2", provider, Path.of("/tmp/review"), prompt),
                new Seat("assignment-3", provider, Path.of("/tmp/review"), prompt)),
                100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Admission>> admitted = ArgumentCaptor.forClass(List.class);
        verify(store).admitRound(
                eq("round-1"), eq("head-1"), admitted.capture(), any());
        assertThat(admitted.getValue())
                .extracting(Admission::costCapUsdMilli)
                .containsExactly(334L, 333L, 333L);
        assertThat(admitted.getValue().stream()
                .mapToLong(Admission::costCapUsdMilli).sum()).isEqualTo(1000);
    }
}
