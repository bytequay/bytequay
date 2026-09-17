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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.function.Function;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * One fixed review operation a provider session exposes to its root agent through the task
 * MCP endpoint.
 *
 * <p>{@code call} receives the tool arguments as one JSON object text and returns tool text. It
 * never throws for an invalid call: field-specific validation errors are tool text the model can
 * repair from, so a provider adapter never sees a review failure.
 */
public record ReviewTool(String name, String description, Map<String, Object> inputSchema, Function<String, String> call)
{
    public static final String NAME_PREFIX = "review_";

    public ReviewTool
    {
        checkNotBlank(name, "name");
        checkNotBlank(description, "description");
        inputSchema = ImmutableMap.copyOf(inputSchema);
        requireNonNull(call, "call is null");
        if (!name.startsWith(NAME_PREFIX) && !name.equals("repository_context_prepare") && !name.equals("repository_context_read")) {
            throw new IllegalArgumentException("Unknown review capability tool name: " + name);
        }
    }
}
