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

import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A task token for ByteQuay's MCP endpoint and what the provider launch needs from it; closing forgets the token and
 * deletes any provider files written for it.
 *
 * @param toolNames agent-facing tools exposed by the token, excluding the provider's permission callback
 * @param instruction the managed guidance for the session's instruction channel, if any tool needs one
 * @param review the review capability resolved from the task when it registered
 * @param configFile Claude's MCP configuration, absent for a provider with its own configuration
 * @param settingsFile Claude's sandbox settings, absent for a provider with its own configuration
 */
public record McpRegistration(
        String token,
        URI endpoint,
        List<String> toolNames,
        /** A digest of every tool the session is given, by name, description and parameters. */
        String toolsDigest,
        Optional<String> instruction,
        Optional<ReviewBridge> review,
        Optional<DevelopmentResultBridge> development,
        Optional<Path> configFile,
        Optional<Path> settingsFile,
        Runnable release)
        implements AutoCloseable
{
    public McpRegistration
    {
        requireNonNull(token, "token is null");
        requireNonNull(endpoint, "endpoint is null");
        toolNames = ImmutableList.copyOf(toolNames);
        requireNonNull(toolsDigest, "toolsDigest is null");
        requireNonNull(instruction, "instruction is null");
        requireNonNull(review, "review is null");
        requireNonNull(development, "development is null");
        requireNonNull(configFile, "configFile is null");
        requireNonNull(settingsFile, "settingsFile is null");
        requireNonNull(release, "release is null");
    }

    @Override
    public void close()
    {
        release.run();
    }
}
