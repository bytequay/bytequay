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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.review.BrainReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class TestBrainReviewToolRetirement
{
    @Test
    void retiredVerdictMutationReturnsAToolError()
    {
        BrainReviewService brain = mock(BrainReviewService.class);
        doThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "LEGACY Brain review is read-only"))
                .when(brain).recordVerdict(
                        anyString(), anyString(), anyString(), anyString(), anyString());
        BrainReviewToolHandlers handlers = new BrainReviewToolHandlers(brain);

        ToolOutcome outcome = handlers.recordReviewVerdict(
                new BrainReviewToolHandlers.RecordReviewVerdictArgs(
                        "round", "approved"),
                new ToolCall(
                        ThreadScope.STAGE, "thread-1", null, AgentRole.TASK,
                        "task-1", "stage-1", "run-1"));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
    }
}
