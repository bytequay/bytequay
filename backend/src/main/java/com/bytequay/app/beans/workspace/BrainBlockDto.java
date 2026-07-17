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
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.bytequay.app.beans.workspace;

import com.bytequay.app.domain.MemoryItem;

import java.util.List;
import java.util.Locale;

/** One applied workspace-brain block in the redesigned three-section UI. */
public record BrainBlockDto(
        long id,
        String category,
        String body,
        String provenance,
        List<String> tags,
        long createdAt)
{
    public static BrainBlockDto from(MemoryItem item)
    {
        String category = switch (item.kind()) {
            case CONVENTION -> "Conventions";
            case DECISION -> "Decisions";
            default -> "Gotchas";
        };
        return new BrainBlockDto(
                item.id(),
                category,
                item.text(),
                item.source().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                item.tags(),
                item.proposedAt().toEpochMilli());
    }
}
