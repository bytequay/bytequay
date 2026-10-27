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

import com.bytequay.core.hosting.HostingCredential;

import java.util.Optional;

public interface HostingCredentialStore
{
    Optional<HostingCredential> find(long accountId);

    /** Last authorization generation, including a deletion tombstone; zero before the first change. */
    long generation(long accountId);

    HostingCredential save(long accountId, String token);

    /** Removes authorization without removing its generation history or retained account identity. */
    void delete(long accountId);
}
