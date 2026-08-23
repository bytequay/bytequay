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
package com.bytequay.app.service;

import com.bytequay.core.store.WorkspaceCredentialStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceCredentialStore
        implements WorkspaceCredentialStore
{
    private record Row(String secret, String login) {}

    private final Map<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<String> getSecret(String workspaceId)
    {
        return Optional.ofNullable(rows.get(workspaceId)).map(Row::secret);
    }

    @Override
    public Optional<String> getLogin(String workspaceId)
    {
        return Optional.ofNullable(rows.get(workspaceId)).map(Row::login);
    }

    @Override
    public void save(String workspaceId, String token, String login)
    {
        rows.put(workspaceId, new Row(token, login));
    }

    @Override
    public void delete(String workspaceId)
    {
        rows.remove(workspaceId);
    }
}
