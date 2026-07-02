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

import java.time.Instant;
import java.util.Optional;

/**
 * Cache of whether a collaborator has write permission on a repo — the test
 * that decides which approvals count toward a task's minimum-approvals gate.
 * Permission rarely changes, so verdicts are cached and only re-fetched once
 * past a TTL.
 */
public interface RepoWriteCollaboratorStore
{
    /** The cached write verdict for {@code (repoFullName, login)} if it was
     *  fetched at or after {@code freshAfter}; empty when absent or stale. */
    Optional<Boolean> find(String repoFullName, String login, Instant freshAfter);

    /** Upsert the write verdict, stamped with {@code fetchedAt}. */
    void save(String repoFullName, String login, boolean canWrite, Instant fetchedAt);
}
