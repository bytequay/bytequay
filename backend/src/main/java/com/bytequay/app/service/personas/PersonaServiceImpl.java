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
import com.bytequay.app.domain.ReviewerPersona;
import com.bytequay.app.domain.ReviewerPersonaRole;
import com.bytequay.app.repository.ReviewerPersonaStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
public class PersonaServiceImpl
        implements PersonaService
{
    private final ReviewerPersonaStore store;

    public PersonaServiceImpl(ReviewerPersonaStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @Override
    public List<ReviewerPersonaDto> listActive()
    {
        return store.listActive().stream()
                .map(ReviewerPersonaDto::fromDomain)
                .toList();
    }

    @Override
    public Optional<ReviewerPersonaDto> findById(String id)
    {
        return store.findById(id).map(ReviewerPersonaDto::fromDomain);
    }

    @Override
    public ReviewerPersonaDto create(PersonaRequest request)
    {
        ReviewerPersonaRole role = validate(request);
        Instant now = Instant.now();
        ReviewerPersona persona = new ReviewerPersona(
                UUID.randomUUID().toString(),
                request.name().strip(),
                request.systemPrompt().strip(),
                role,
                /* active */ true,
                now,
                now);
        store.save(persona);
        return ReviewerPersonaDto.fromDomain(persona);
    }

    @Override
    public ReviewerPersonaDto update(String id, PersonaRequest request)
    {
        ReviewerPersonaRole role = validate(request);
        ReviewerPersona existing = store.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("persona not found: " + id));
        ReviewerPersona updated = new ReviewerPersona(
                existing.id(),
                request.name().strip(),
                request.systemPrompt().strip(),
                role,
                existing.active(),
                existing.createdAt(),
                Instant.now());
        store.save(updated);
        return ReviewerPersonaDto.fromDomain(updated);
    }

    @Override
    public void softDelete(String id)
    {
        store.softDelete(id);
    }

    /** Common validation for create + update. Returns the parsed
     *  {@link ReviewerPersonaRole} so callers don't reparse. */
    private static ReviewerPersonaRole validate(PersonaRequest request)
    {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.name() == null || request.name().strip().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (request.systemPrompt() == null || request.systemPrompt().strip().isEmpty()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        if (request.role() == null || request.role().strip().isEmpty()) {
            throw new IllegalArgumentException("role must be LEAD or REVIEWER");
        }
        try {
            return ReviewerPersonaRole.valueOf(request.role().strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role must be LEAD or REVIEWER, got: " + request.role());
        }
    }
}
