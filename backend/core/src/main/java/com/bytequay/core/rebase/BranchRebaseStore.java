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
package com.bytequay.core.rebase;

import java.util.List;
import java.util.Optional;

/** Persists versioned branch rebases without deciding transitions. */
public interface BranchRebaseStore
{
    int MAX_LIST_LIMIT = 200;

    BranchRebase createOrGet(BranchRebase rebase);

    Optional<BranchRebase> findByIdempotencyKey(String idempotencyKey);

    Optional<BranchRebase> find(BranchRebaseId rebaseId);

    List<BranchRebase> listRunning(int limit);

    BranchRebase save(BranchRebase rebase);
}
