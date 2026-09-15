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
package com.bytequay.app.context;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;

/** An explicitly edited lesson referencing local review evidence/message IDs, never caller-supplied excerpts. */
public record SaveContextLesson(
        String scopeId,
        String title,
        String summary,
        String body,
        String reviewId,
        String runId,
        List<String> evidenceRefs,
        Optional<String> messageId)
{
    public SaveContextLesson
    {
        checkText(reviewId, "reviewId", 200);
        checkText(runId, "runId", 200);
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        checkOptionalText(messageId, "messageId", 200);
    }
}
