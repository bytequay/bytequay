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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An important assertion with explicit epistemic status and source references. */
public record ContextClaim(String text, Basis basis, List<String> sourceIds)
{
    public ContextClaim
    {
        ContextLimits.text(text, "claim", 2000);
        requireNonNull(basis, "basis is null");
        sourceIds = ImmutableList.copyOf(sourceIds);
        ContextLimits.unique(sourceIds, "claim sources", ContextLimits.MAX_SOURCES);
        sourceIds.forEach(id -> ContextLimits.text(id, "source id", 500));
        checkArgument(!sourceIds.isEmpty() || basis == Basis.INFERENCE || basis == Basis.QUESTION, "This claim needs a source");
    }

    public enum Basis
    {
        DOCUMENTED,
        RECORDED_DECISION,
        INFERENCE,
        QUESTION,
        USER_LESSON
    }
}
