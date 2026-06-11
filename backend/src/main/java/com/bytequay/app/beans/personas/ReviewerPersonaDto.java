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
package com.bytequay.app.beans.personas;

import com.bytequay.app.domain.ReviewerPersona;
import com.bytequay.app.domain.ReviewerPersonaRole;

import java.time.Instant;

/**
 * Wire shape for {@code GET /api/personas} responses. Direct
 * one-to-one with {@link ReviewerPersona} — only the
 * {@link ReviewerPersonaRole} is flattened to a string for the
 * frontend's convenience.
 */
public record ReviewerPersonaDto(
        String id,
        String name,
        String systemPrompt,
        String role,
        boolean active,
        Instant createdAt,
        Instant updatedAt)
{
    public static ReviewerPersonaDto fromDomain(ReviewerPersona p)
    {
        return new ReviewerPersonaDto(
                p.id(),
                p.name(),
                p.systemPrompt(),
                p.role().name(),
                p.active(),
                p.createdAt(),
                p.updatedAt());
    }
}
