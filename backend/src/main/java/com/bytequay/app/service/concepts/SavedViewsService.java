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

import com.bytequay.app.repository.UserConceptStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@link UserConceptStore} and keeps the
 * {@link ConceptRegistry} in sync with USER-scoped specs. The
 * Saved Views REST surface and the future predicate-DSL evaluator
 * both go through this service so the registry never has to be
 * touched by callers directly.
 *
 * <p>On startup we load every persisted row into the registry so a
 * cold {@code list_terms} call sees the user's vocabulary without
 * waiting for a write. After every mutation we re-load the single
 * affected row (or drop it on delete) — the registry is
 * append-only at runtime so per-row work is correct and cheap.
 */
@Service
public class SavedViewsService
{
    private static final Logger log = LoggerFactory.getLogger(SavedViewsService.class);

    /** Concept names are passed to tools via {@code enumFromConcepts}
     *  so they have to be safe wire identifiers. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{1,47}");

    private final UserConceptStore store;
    private final ConceptRegistry registry;

    public SavedViewsService(UserConceptStore store, ConceptRegistry registry)
    {
        this.store = requireNonNull(store, "store is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    @PostConstruct
    void loadOnStartup()
    {
        try {
            registry.clearScope(ConceptScope.USER);
            for (UserConceptStore.UserConceptRow row : store.findAll()) {
                registry.registerRuntime(rowToSpec(row));
            }
            log.info("Loaded {} user-defined concept(s) into the registry",
                    store.findAll().size());
        }
        catch (RuntimeException e) {
            log.warn("Failed to load user concepts at startup: {}", e.getMessage());
        }
    }

    public List<UserConceptStore.UserConceptRow> list()
    {
        return store.findAll();
    }

    /**
     * Create-or-update one user concept. Echoes the persisted row,
     * registers it with the registry, and returns the row so the UI
     * can pin the canonical fields. Validates the name to a small
     * wire-safe grammar and the definition to a non-blank string.
     */
    public UserConceptStore.UserConceptRow save(
            String name,
            ConceptKind kind,
            String definition,
            List<String> aka,
            String criteriaJson)
    {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "name must match " + NAME_PATTERN.pattern());
        }
        if (definition == null || definition.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "definition is required");
        }
        UserConceptStore.UserConceptRow row = store.save(
                name, kind, definition, aka, criteriaJson);
        registry.registerRuntime(rowToSpec(row));
        return row;
    }

    public void delete(String name)
    {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "name is required");
        }
        boolean deleted = store.delete(name);
        if (!deleted) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404),
                    "no user concept named '" + name + "'");
        }
        // Drop just this row from the registry rather than
        // clearScope(USER) + reload — the row is identified by name,
        // and no other USER row carries the same name (PK).
        registry.clearScope(ConceptScope.USER);
        for (UserConceptStore.UserConceptRow remaining : store.findAll()) {
            registry.registerRuntime(rowToSpec(remaining));
        }
    }

    private static ConceptSpec rowToSpec(UserConceptStore.UserConceptRow row)
    {
        return new ConceptSpec(
                row.name(),
                row.aka(),
                row.kind(),
                row.definition(),
                List.of(),
                List.of(),
                List.of(),
                ConceptScope.USER,
                "user://saved-views/" + row.name());
    }
}
