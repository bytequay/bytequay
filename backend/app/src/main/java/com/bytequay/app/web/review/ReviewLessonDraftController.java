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
package com.bytequay.app.web.review;

import com.bytequay.app.review.ReviewLessonDraft;
import com.bytequay.app.review.ReviewLessonDraftService;
import com.bytequay.core.review.ReviewInvalidRequestException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/agent-review")
public class ReviewLessonDraftController
{
    private final ReviewLessonDraftService drafts;

    public ReviewLessonDraftController(ReviewLessonDraftService drafts)
    {
        this.drafts = requireNonNull(drafts, "drafts is null");
    }

    @PostMapping("/{reviewId}/lesson-draft")
    public ReviewLessonDraft draft(@PathVariable String reviewId, @RequestBody DraftRequest request)
    {
        if (request == null) {
            throw new ReviewInvalidRequestException("Select a saved review discussion before drafting a lesson");
        }
        return drafts.draft(reviewId, request.messageId());
    }

    public record DraftRequest(String messageId) {}
}
