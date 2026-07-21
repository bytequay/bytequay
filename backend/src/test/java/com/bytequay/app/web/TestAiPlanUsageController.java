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

import com.bytequay.app.service.ai.PlanUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAiPlanUsageController
{
    @Test
    void refreshMapsBestEffortCliFailureToServiceUnavailable()
    {
        PlanUsageService service = mock(PlanUsageService.class);
        when(service.refreshClaude()).thenThrow(
                new IllegalStateException("Claude CLI did not return plan usage"));

        AiPlanUsageController controller = new AiPlanUsageController(service);

        // A failed /usage scrape is an upstream 503, not an unhandled 500.
        assertThatThrownBy(controller::refreshClaude)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void refreshPassesUsageThroughOnSuccess()
    {
        PlanUsageService service = mock(PlanUsageService.class);
        PlanUsageService.PlanUsage usage = new PlanUsageService.PlanUsage(List.of());
        when(service.refreshClaude()).thenReturn(usage);

        assertThat(new AiPlanUsageController(service).refreshClaude()).isSameAs(usage);
    }
}
