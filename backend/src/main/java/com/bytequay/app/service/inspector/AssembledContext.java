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

import com.bytequay.app.service.tools.TurnRequest;
import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Read-only view of one turn's complete prompt context — the 8
 * {@link ContextSection} entries in serialised order plus the
 * raw {@link TurnRequest} wire bytes the lane would actually send.
 *
 * <p>The two surfaces (sections and wire) describe the same bytes:
 * concatenating every section's {@code body} yields the same
 * string the lane would serialise to the provider. The viewer's
 * section nav reads {@link #sections()}; the full-request view
 * reads {@link #wire()} verbatim.
 *
 * <p>Always assembled with {@code dryRun = true} from the
 * endpoint's perspective — this record is never the input to a
 * real provider call. The constructor doesn't enforce that
 * because the underlying TurnRequest may also be used by a real
 * turn elsewhere; the gating is at the endpoint layer.
 */
public record AssembledContext(
        ContextScope scope,
        String scopeId,
        ContextMeta meta,
        List<ContextSection> sections,
        TurnRequest wire)
{
    public AssembledContext
    {
        sections = sections == null ? List.of() : ImmutableList.copyOf(sections);
    }
}
