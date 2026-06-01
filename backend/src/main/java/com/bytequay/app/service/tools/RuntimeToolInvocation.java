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
 * Outcome of dispatching one tool call. {@code payload} is the
 * Jackson-serialisable value the model will see as the tool result —
 * a record, a {@link java.util.List}, a {@link java.util.Map}, etc.
 * Serialisation happens once at the wire boundary (the lane that
 * consumes this outcome), so tool handlers stay free of JSON
 * plumbing and the bytes the model sees come from one place.
 *
 * @param payload  the tool result value. Must be Jackson-serialisable;
 *                 typically a record built specifically as the tool's
 *                 wire DTO.
 * @param isError  true when the dispatch produced a domain error the
 *                 model should treat as a failure (e.g. "skill X not
 *                 found"); false for a successful call. Maps to
 *                 Anthropic's {@code is_error} flag (or the OpenAI
 *                 "tool error" convention) at the wire boundary.
 */
public record RuntimeToolInvocation(Object payload, boolean isError)
{
    public static RuntimeToolInvocation ok(Object payload)
    {
        return new RuntimeToolInvocation(payload, false);
    }

    public static RuntimeToolInvocation error(String message)
    {
        return new RuntimeToolInvocation(new ErrorPayload(message), true);
    }

    /** Wire shape for an error result: {@code {"error": "<message>"}}. */
    public record ErrorPayload(String error) {}
}
