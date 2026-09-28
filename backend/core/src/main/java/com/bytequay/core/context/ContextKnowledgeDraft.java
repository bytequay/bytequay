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

import java.util.Optional;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Generated or user-edited knowledge awaiting explicit activation. */
public record ContextKnowledgeDraft(String id, ContextEntry entry, Optional<Long> replacesVersion, String baselineSha, String explanation)
{
    public ContextKnowledgeDraft
    {
        ContextLimits.text(id, "draft id", 200);
        requireNonNull(entry, "entry is null");
        checkArgument(entry.kind() == KNOWLEDGE && entry.enabled(), "Draft must contain enabled knowledge");
        requireNonNull(replacesVersion, "replacesVersion is null");
        replacesVersion.ifPresent(version -> checkArgument(version > 0, "Replacement version must be positive"));
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid draft baseline");
        ContextLimits.text(explanation, "draft explanation", 2000);
    }
}
