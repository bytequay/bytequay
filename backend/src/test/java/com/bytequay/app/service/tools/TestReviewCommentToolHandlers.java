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

import com.bytequay.app.service.review.ReviewCommentService;
import com.bytequay.app.service.tools.ReviewCommentToolHandlers.ResolveReviewCommentArgs;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestReviewCommentToolHandlers
{
    private final ReviewCommentService reviewComments = mock(ReviewCommentService.class);
    private final ReviewCommentToolHandlers handlers = new ReviewCommentToolHandlers(reviewComments);

    private final ToolCall call = new ToolCall("thread-1", null, AgentRole.TASK);

    @Test
    void resolvesAComment()
    {
        UUID id = UUID.randomUUID();
        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs(id.toString()), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(reviewComments).resolve(id);
    }

    @Test
    void rejectsAMalformedId()
    {
        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs("not-a-uuid"), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(reviewComments, never()).resolve(any());
    }
}
