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
package com.bytequay.app.service.pr.filters;

import java.time.Instant;

/**
 * A named predicate over a collection of {@code T}. Each
 * implementation also carries a
 * {@link com.bytequay.app.service.concepts.Concept @Concept} so the
 * concept axis can describe what the filter <em>means</em> and tool
 * params can reference it by name via
 * {@link com.bytequay.app.service.tools.ToolParam#enumFromConcepts()}.
 *
 * <p>The leverage: when the agent calls
 * {@code list_prs(filter:"urgent")}, dispatch resolves the named
 * filter, applies it, and returns the matching items — no per-call
 * interpretation, no parallel definition.
 *
 * @param <T> the item type the predicate is evaluated against
 */
public interface NamedFilter<T>
{
    /**
     * Canonical filter name. Matches the {@code @Concept(name=…)} on
     * the implementing class so cross-referencing the concept
     * registry round-trips.
     */
    String name();

    /**
     * True iff {@code item} passes the filter at the given wall-
     * clock instant. {@code now} is injected (rather than read from
     * the system clock inside the impl) so tests can pin a fixed
     * time and the filter stays deterministic.
     */
    boolean matches(T item, Instant now);
}
