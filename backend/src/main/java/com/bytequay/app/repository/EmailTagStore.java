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

import com.bytequay.app.domain.EmailTag;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for per-account email tag rules. JPA entity
 * stays package-private inside {@code repository.sqlite}; the service
 * layer only sees this interface and the {@link EmailTag} record.
 */
public interface EmailTagStore
{
    /** Inserts or updates a tag. Caller supplies the id (UUID minted by the service). */
    void save(EmailTag tag);

    /** Returns the tag by id, or empty when not found. */
    Optional<EmailTag> findById(String id);

    /** All tags for an account in id order. */
    List<EmailTag> listByAccount(String accountEmail);

    /** Deletes a tag. No-op when the row doesn't exist. */
    void deleteById(String id);
}
