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

import java.util.Optional;

/**
 * Per-workspace GitHub token overriding the global account credential.
 * Implementations store the secret encrypted; the login is the GitHub
 * account the token authenticated as when it was saved.
 */
public interface WorkspaceCredentialStore
{
    Optional<String> getSecret(String workspaceId);

    Optional<String> getLogin(String workspaceId);

    void save(String workspaceId, String token, String login);

    void delete(String workspaceId);
}
