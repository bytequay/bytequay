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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A concrete discovery question and the scope and evidence used to assess one required perspective. */
public record ReviewPerspectiveAssessment(
        Perspective perspective,
        String question,
        List<String> responsibilities,
        List<String> paths,
        List<String> evidenceRefs,
        Status status,
        String explanation)
{
    public ReviewPerspectiveAssessment
    {
        requireNonNull(perspective, "perspective is null");
        checkArgument(!requireNonNull(question, "question is null").isBlank(), "question is blank");
        responsibilities = ImmutableList.copyOf(responsibilities);
        paths = ImmutableList.copyOf(paths);
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        requireNonNull(status, "status is null");
        checkArgument(!requireNonNull(explanation, "explanation is null").isBlank(), "explanation is blank");
    }

    public enum Perspective
    {
        BEHAVIOR,
        INTERACTIONS,
        DESIGN
    }

    public enum Status
    {
        CHECKED,
        NOT_APPLICABLE,
        BLOCKED
    }
}
