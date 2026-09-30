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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Pattern;

/** Extracts an exact receipt identity from a completed local context tool result without copying note text into task activity. */
public final class ContextReceiptRef
{
    public static final String SUMMARY_PREFIX = "context-receipt:";
    private static final Pattern REF = Pattern.compile("^context-(?:search|note):(?:[0-9a-f]{64}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$");

    private ContextReceiptRef() {}

    public static Optional<String> fromResult(JsonNode content)
    {
        return ToolResults.object(content).flatMap(result -> {
            JsonNode searchRef = result.path("searchRef");
            if (searchRef.isTextual() && (result.path("hits").isArray() || result.path("result").path("hits").isArray())
                    && REF.matcher(searchRef.asText()).matches() && searchRef.asText().startsWith("context-search:")) {
                return Optional.of(searchRef.asText());
            }
            JsonNode evidenceRef = result.path("evidenceRef");
            if (result.path("status").asText().equals("AVAILABLE") && evidenceRef.isTextual()
                    && REF.matcher(evidenceRef.asText()).matches() && evidenceRef.asText().startsWith("context-note:")) {
                return Optional.of(evidenceRef.asText());
            }
            return Optional.empty();
        });
    }
}
