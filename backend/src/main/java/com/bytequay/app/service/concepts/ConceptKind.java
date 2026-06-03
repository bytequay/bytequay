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
package com.bytequay.app.service.concepts;

/**
 * Coarse classification for a {@link Concept}. Drives the
 * {@code list_terms} meta-tool's filter and shapes how an agent
 * thinks about what the term means before it acts on it.
 *
 * <ul>
 *   <li>{@link #NOUN} — a thing the system reasons about (PR, Task,
 *       Thread, Trunk).</li>
 *   <li>{@link #STATE} — a status value an entity can be in
 *       (AWAITING_REVIEW, NEEDS_ATTENTION, parked, shipped).</li>
 *   <li>{@link #FILTER} — a named predicate over a collection
 *       ({@code urgent}, {@code stale}, {@code mine_open}); these
 *       are what {@code @ToolParam(enumFromConcepts=…)} primarily
 *       references.</li>
 *   <li>{@link #VERB} — an action the system or the agent can take
 *       (ship, next, request_review).</li>
 * </ul>
 */
public enum ConceptKind
{
    NOUN,
    STATE,
    FILTER,
    VERB
}
