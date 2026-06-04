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
package com.bytequay.app.service.inspector;

/**
 * Trace from one chunk of an assembled prompt section back to its
 * source. v1 (Phases A-D) attaches one coarse-grained Provenance
 * per {@link ContextSection} naming the axis that produced it; the
 * fine-grained, per-paragraph variant lives in Phase E and is
 * design-gated.
 *
 * @param kind     short axis name — {@code "skill"}, {@code "brain"},
 *                 {@code "memory_item"}, {@code "concept"},
 *                 {@code "history"}, {@code "role"}, {@code "tool"}
 * @param label    short clickable label for the UI chip
 * @param href     app-internal link the inspector resolves (e.g.
 *                 {@code /workspace/memory#item-42}); {@code null}
 *                 when there's no actionable destination
 * @param byteRange optional {@code "start-end"} byte range inside
 *                 the parent section body; {@code null} for
 *                 whole-section provenance
 */
public record Provenance(String kind, String label, String href, String byteRange) {}
