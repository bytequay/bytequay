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
package com.bytequay.app.service.tools;

/**
 * Outcome of dispatching one tool call. {@code result} is the JSON
 * the model sees back as the tool result; {@code isError} maps to
 * Anthropic's {@code is_error} flag (or the OpenAI "tool error"
 * convention) so providers can mark it without our caller having to
 * parse the body.
 *
 * @param result   the JSON the model receives as the tool result;
 *                 typically an object, sometimes an array. Always a
 *                 valid JSON document.
 * @param isError  true when the dispatch produced a domain error the
 *                 model should treat as a failure (e.g. "skill X not
 *                 found"); false for a successful call.
 */
public record RuntimeToolInvocation(String result, boolean isError)
{
    public static RuntimeToolInvocation ok(String result)
    {
        return new RuntimeToolInvocation(result, false);
    }

    public static RuntimeToolInvocation error(String message)
    {
        return new RuntimeToolInvocation(
                "{\"error\":\"" + escape(message) + "\"}",
                true);
    }

    private static String escape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
