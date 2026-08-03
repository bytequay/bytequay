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
package com.bytequay.app.beans.mcp;

import java.util.List;

/**
 * Result for {@code tools/call}: MCP wraps tool output in a {@code
 * content} array of typed entries. We only ever emit a single
 * {@code text}-typed entry, so the convenience factories cover the
 * common case.
 *
 * <p>{@code isError} distinguishes MCP's two error channels. A tool
 * <em>execution</em> error — bad arguments, a rejected submission, a
 * business-rule violation — is a successful JSON-RPC response carrying
 * {@code isError: true}, which the spec says clients SHOULD hand to the
 * model so it can correct itself in the same session. A <em>protocol</em>
 * error (unknown tool, malformed request) stays a JSON-RPC error, which
 * the spec notes is "less likely to result in successful recovery".
 * Returning a recoverable failure on the protocol channel — or on the
 * {@link DenyEnvelope} permission channel, which ends the turn — costs
 * the model the chance to retry.
 */
public record ToolCallResult(List<ToolContent> content, boolean isError)
{
    /** Wrap a single text payload as a successful tool-call result. */
    public static ToolCallResult text(String text)
    {
        return new ToolCallResult(List.of(ToolContent.text(text)), false);
    }

    /** Wrap a tool-execution failure the model is expected to read and
     *  correct without the turn ending. */
    public static ToolCallResult error(String message)
    {
        return new ToolCallResult(List.of(ToolContent.text(message)), true);
    }
}
