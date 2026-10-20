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
package com.bytequay.agent.cli.mcp;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class McpTestSupport
{
    private McpTestSupport() {}

    static Optional<McpRegistration> registerCodex(PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor)
    {
        return broker.registerTools(taskId, directory, policy,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of());
    }

    static McpRegistration registerClaude(PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor)
    {
        return broker.register(taskId, directory, directory, directory, policy, SessionAccess.FULL,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of());
    }
}
