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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** The last working day's look back over the week: what merged and what was suggested but is still open. */
public record AgendaRecap(String text, List<Link> merged, List<Link> slipped)
{
    public AgendaRecap
    {
        requireNonNull(text, "text is null");
        merged = ImmutableList.copyOf(merged);
        slipped = ImmutableList.copyOf(slipped);
    }

    public record Link(String ref, AgendaTarget target)
    {
        public Link
        {
            checkText(ref, "ref");
            requireNonNull(target, "target is null");
        }
    }
}
