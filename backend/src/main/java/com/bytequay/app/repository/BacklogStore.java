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

import com.bytequay.app.domain.BacklogItem;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for per-thread {@link BacklogItem}s. */
public interface BacklogStore
{
    /** Insert or update an item; returns the persisted row. */
    BacklogItem save(BacklogItem item);

    /** Items on a thread, oldest-first. */
    List<BacklogItem> findByThread(String threadId);

    /** One item by id. */
    Optional<BacklogItem> findById(String id);

    /** Permanently remove an item. No-op when the id is unknown. */
    void delete(String id);
}
