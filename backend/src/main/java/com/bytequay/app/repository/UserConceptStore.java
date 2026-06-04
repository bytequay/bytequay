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
package com.bytequay.app.repository;

import com.bytequay.app.service.concepts.ConceptKind;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for user-authored ({@link
 * com.bytequay.app.service.concepts.ConceptScope#USER USER}-scoped)
 * concepts — the Saved Views surface. v1 holds the data needed for
 * vocabulary lookup; the optional {@code criteriaJson} blob is
 * carried through so a predicate DSL can land later without a
 * second migration.
 */
public interface UserConceptStore
{
    /** Persistence shape — mirrors the schema 1:1. */
    record UserConceptRow(
            String name,
            ConceptKind kind,
            String definition,
            List<String> aka,
            String criteriaJson,
            long createdAtMs,
            long updatedAtMs) {}

    /** All rows, ordered alphabetically by name. */
    List<UserConceptRow> findAll();

    Optional<UserConceptRow> findByName(String name);

    /** UPSERT — inserts a new row or replaces the existing one for
     *  the same {@code name}. */
    UserConceptRow save(
            String name,
            ConceptKind kind,
            String definition,
            List<String> aka,
            String criteriaJson);

    /** Returns true iff a row was actually deleted (i.e. the name
     *  existed). */
    boolean delete(String name);
}
