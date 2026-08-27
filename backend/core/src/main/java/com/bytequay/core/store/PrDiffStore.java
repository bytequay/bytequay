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
package com.bytequay.core.store;

import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.StoredPrDiff;

import java.util.Optional;
import java.util.Set;

public interface PrDiffStore
{
    Optional<StoredPrDiff> find(long prId, String baseSha, String headSha);

    void save(long prId, String baseSha, String headSha, StoredPrDiff diff);

    /** The ETags stored alongside the default diff, or none when it was never fetched conditionally. */
    PrDiffEtags findDefaultEtags(long prId);

    /** Replaces the default diff and the ETags that will validate it next time. */
    void saveDefault(long prId, StoredPrDiff diff, PrDiffEtags etags);

    void saveFileContent(long prId, String baseSha, String headSha, String path, DiffFileContent content);

    void deleteByPrIds(Set<Long> prIds);
}
