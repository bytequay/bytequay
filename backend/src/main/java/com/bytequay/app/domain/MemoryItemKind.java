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
package com.bytequay.app.domain;

/**
 * Coarse classification for a {@link MemoryItem}. The six values are
 * the closed set the distiller and the meta-tools share — adding a
 * kind is a deliberate cross-cutting change (DB CHECK constraint +
 * filter UIs + the recall_memory enum).
 *
 * <ul>
 *   <li>{@link #DECISION} — a load-bearing choice the user / team
 *       made and wants future work to honour.</li>
 *   <li>{@link #BLOCKER} — something that's stopping progress until
 *       someone clears it.</li>
 *   <li>{@link #CONVENTION} — how the codebase / team does X (style,
 *       naming, structural rule).</li>
 *   <li>{@link #FOCUS_SHIFT} — what the active focus is right now,
 *       the only kind that may opt into auto-apply once Phase F
 *       lands.</li>
 *   <li>{@link #OPEN_QUESTION} — a question whose answer changes
 *       downstream work; surface it on recall until resolved.</li>
 *   <li>{@link #RECURRING_PATTERN} — a problem / opportunity the
 *       distiller noticed more than once across threads.</li>
 * </ul>
 */
public enum MemoryItemKind
{
    DECISION,
    BLOCKER,
    CONVENTION,
    FOCUS_SHIFT,
    OPEN_QUESTION,
    RECURRING_PATTERN
}
