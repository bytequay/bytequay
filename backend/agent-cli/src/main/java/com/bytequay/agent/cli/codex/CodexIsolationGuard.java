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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;

import static java.util.Objects.requireNonNull;

/**
 * Proves that no inherited Codex capability applies to a task before its first turn.
 *
 * <p>One App Server process serves every task, so process-wide configuration is shared rather than
 * partitioned. Safety therefore comes from proving these capabilities are absent, not from scoping
 * them per task. Every check fails closed: an unexpected shape, a request failure, or anything that
 * cannot be inspected makes the lane unavailable rather than assumed safe.
 *
 * <p>This currently covers managed requirements and lifecycle hooks. Skill origin and config-layer
 * rule sources are proven by the compatibility gate but are not yet enforced here.
 */
final class CodexIsolationGuard
{
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String REQUIREMENTS_FIELD = "requirements";

    private final CodexAppServerClient client;

    CodexIsolationGuard(CodexAppServerClient client)
    {
        this.client = requireNonNull(client, "client is null");
    }

    /**
     * Verifies the guards that apply to one task working directory.
     *
     * @param cwd task worktree the turn will run against
     * @throws AgentUnavailableException when any capability cannot be proven absent
     */
    void verify(Path cwd)
    {
        requireNonNull(cwd, "cwd is null");
        verifyNoManagedRequirements();
        verifyNoLifecycleHooks(cwd);
    }

    /**
     * Requires managed policy to report exactly one explicitly null requirements field.
     *
     * <p>This reads the raw response on purpose. The rule is about unknown siblings, and decoding
     * into a record would silently ignore exactly the field that should fail the check, because the
     * codec tolerates additive properties everywhere else.
     */
    private void verifyNoManagedRequirements()
    {
        JsonNode response = request("configRequirements/read");
        if (!response.isObject()) {
            throw unavailable("configRequirements/read returned no policy object");
        }
        Iterator<String> fields = response.fieldNames();
        if (!fields.hasNext() || !fields.next().equals(REQUIREMENTS_FIELD) || fields.hasNext()) {
            throw unavailable("configRequirements/read returned an unknown managed-policy field");
        }
        if (!response.get(REQUIREMENTS_FIELD).isNull()) {
            throw unavailable("Codex managed requirements apply to this host");
        }
    }

    private void verifyNoLifecycleHooks(Path cwd)
    {
        CodexProtocol.HooksListResponse response = client.request(
                "hooks/list",
                new CodexProtocol.CwdsParams(ImmutableList.of(cwd.toString())),
                CodexProtocol.HooksListResponse.class,
                REQUEST_TIMEOUT);
        CodexProtocol.HooksListEntry entry = response.data().stream()
                .filter(candidate -> candidate.cwd().equals(cwd.toString()))
                .findFirst()
                .orElseThrow(() -> unavailable("hooks/list reported nothing for the task worktree"));
        if (!entry.errors().isEmpty()) {
            throw unavailable("Codex hook discovery failed for the task worktree");
        }
        if (!entry.hooks().isEmpty()) {
            throw unavailable("Codex lifecycle hooks apply to the task worktree");
        }
    }

    private JsonNode request(String method)
    {
        try {
            return client.request(method, new CodexProtocol.EmptyParams(), JsonNode.class, REQUEST_TIMEOUT);
        }
        catch (RuntimeException failure) {
            throw unavailable(method + " could not be verified: " + failure.getMessage());
        }
    }

    private static AgentUnavailableException unavailable(String message)
    {
        return new AgentUnavailableException("Codex isolation check failed: " + message);
    }
}
