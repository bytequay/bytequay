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

import com.bytequay.app.domain.ReviewerPersona;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for user-defined reviewer personas. The JPA
 * entity stays package-private inside {@code repository.sqlite}; the
 * service layer only sees this interface and the {@link ReviewerPersona}
 * record.
 */
public interface ReviewerPersonaStore
{
    /** Inserts or updates a persona. The caller supplies the id
     *  (UUID minted by the service on create; preserved on update). */
    void save(ReviewerPersona persona);

    /** Returns the persona by id, or empty when not found. */
    Optional<ReviewerPersona> findById(String id);

    /** Active personas ordered by name. The Start Review dialog
     *  reads this verbatim to populate its picker. */
    List<ReviewerPersona> listActive();

    /** Soft-deletes a persona by flipping {@code is_active} to 0.
     *  No-op when the row doesn't exist. */
    void softDelete(String id);
}
