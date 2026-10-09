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
package com.bytequay.core.review;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A review's conclusion on one pull-request thread the user started. The thread's place and opening words are kept as
 * they were when it was judged, so the conclusion still reads after the discussion moves on.
 *
 * @param line the thread's line; absent when GitHub reports none
 * @param opening the start of the user's first comment
 * @param resolved the thread was marked resolved on GitHub, which alone does not make it addressed
 */
public record ReviewThreadVerdict(
        String threadId,
        Verdict verdict,
        String explanation,
        List<String> evidenceRefs,
        String path,
        Optional<Integer> line,
        String opening,
        boolean resolved)
{
    public ReviewThreadVerdict
    {
        checkArgument(!requireNonNull(threadId, "threadId is null").isBlank(), "threadId is blank");
        requireNonNull(verdict, "verdict is null");
        checkArgument(!requireNonNull(explanation, "explanation is null").isBlank(), "explanation is blank");
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        requireNonNull(path, "path is null");
        requireNonNull(line, "line is null");
        requireNonNull(opening, "opening is null");
    }

    public enum Verdict
    {
        ADDRESSED,
        PARTLY_ADDRESSED,
        NOT_ADDRESSED,
        /** The author answered or pushed back, and the next move is the user's. */
        AWAITING_REPLY
    }
}
