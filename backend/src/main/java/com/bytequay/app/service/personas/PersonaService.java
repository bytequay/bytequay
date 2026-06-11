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
package com.bytequay.app.service.personas;

import com.bytequay.app.beans.personas.PersonaRequest;
import com.bytequay.app.beans.personas.ReviewerPersonaDto;

import java.util.List;
import java.util.Optional;

/**
 * Business contract for user-defined reviewer personas. The HTTP
 * surface ({@code /api/personas}) lives in {@code PersonaController},
 * which is a thin delegator over this interface.
 */
public interface PersonaService
{
    /** Active personas only — soft-deleted entries don't surface to
     *  the UI. Sorted by name for deterministic rendering. */
    List<ReviewerPersonaDto> listActive();

    Optional<ReviewerPersonaDto> findById(String id);

    /** Mint a new persona. Validates the request shape (non-blank
     *  name + prompt, role in {LEAD, REVIEWER}) and returns the
     *  freshly-stored row. */
    ReviewerPersonaDto create(PersonaRequest request);

    /** Update an existing persona's name / prompt / role. Returns the
     *  updated row. Throws {@code 404} via {@code IllegalArgumentException}
     *  if the id isn't on file. */
    ReviewerPersonaDto update(String id, PersonaRequest request);

    /** Soft-delete the persona. No-op if the id is already gone. */
    void softDelete(String id);
}
