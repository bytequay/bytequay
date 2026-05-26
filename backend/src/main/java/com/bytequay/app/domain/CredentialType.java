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
package com.bytequay.app.domain;

/**
 * Coarse category for credentials stored in the vault. Each row in the
 * {@code credentials} table is uniquely identified by the pair (type, name),
 * letting one table hold the singleton GitHub PAT, per-repo PATs, and any
 * number of AI provider keys without colliding.
 *
 * <p>Conventions for the {@code name} component:
 * <ul>
 *   <li>{@link #ACCOUNT}     — singleton; name is always {@code "github"}.</li>
 *   <li>{@link #REPO}        — name is the repo's full slug, {@code "owner/repo"}.</li>
 *   <li>{@link #AI}          — name is the provider id ({@code "anthropic"},
 *       {@code "openai"}, {@code "local"}).</li>
 *   <li>{@link #INTEGRATION} — name is the integration id
 *       (e.g., {@code "github-oauth-app"} for the OAuth app credentials).</li>
 *   <li>{@link #MCP}         — name is the MCP service id
 *       ({@code "slack"}, {@code "linear"}, etc.); per-service
 *       extra fields (transport, auth-kind, server URL, env-var
 *       name) ride along in the row's {@code configJson}.</li>
 * </ul>
 */
public enum CredentialType
{
    ACCOUNT,
    REPO,
    AI,
    INTEGRATION,
    MCP
}
