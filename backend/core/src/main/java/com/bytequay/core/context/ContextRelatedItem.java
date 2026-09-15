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
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One selected historical source; absence of an ancestry result is explicitly unknown. */
public record ContextRelatedItem(String sourceId, List<String> matchReasons, Optional<Boolean> presentInReviewedBase)
{
    public ContextRelatedItem
    {
        ContextLimits.text(sourceId, "related source evidence reference", 200);
        matchReasons = ImmutableList.copyOf(matchReasons);
        checkArgument(!matchReasons.isEmpty() && matchReasons.size() <= 41, "Related source needs bounded matching reasons");
        matchReasons.forEach(reason -> ContextLimits.text(reason, "matching reason", 2000));
        requireNonNull(presentInReviewedBase, "presentInReviewedBase is null");
    }
}
