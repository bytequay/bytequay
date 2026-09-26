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

import java.util.List;
import java.util.Optional;

/** Jira pairing credentials are encrypted by the persistence adapter and never returned to the renderer. */
public interface JiraConnectionStore
{
    List<String> ids();

    Optional<Connection> find(String id);

    void save(Connection connection);

    void delete(String id);

    record Connection(String id, String brokerUrl, String pairingToken, String stateJson)
    {
        @Override
        public String toString()
        {
            return "JiraConnection[" + id + "]";
        }
    }
}
