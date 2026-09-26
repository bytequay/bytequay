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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentStorageException;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

final class AgentEventOutboxJson
{
    private AgentEventOutboxJson() {}

    public static String encodeSource(AgentEventSource source)
    {
        return AGENT.write(source, "event source");
    }

    public static AgentEventSource decodeSource(String json)
    {
        return AGENT.read(json, AgentEventSource.class, "event source");
    }

    public static String encodePayload(AgentEventOutboxPayload payload)
    {
        return switch (payload) {
            case AgentEventOutboxPayload.Inline inline -> AGENT.write(inline.payload(), "event payload");
            case AgentEventOutboxPayload.UserMessageBlob blob -> AGENT.write(blob, "event payload");
        };
    }

    public static AgentEventOutboxPayload decodePayload(
            AgentEventPayload.Kind kind,
            boolean blobBacked,
            String json)
    {
        if (blobBacked) {
            if (kind != AgentEventPayload.Kind.USER_MESSAGE) {
                throw new AgentStorageException("Blob-backed outbox payload is not a user message");
            }
            return AGENT.read(json, AgentEventOutboxPayload.UserMessageBlob.class, "event payload");
        }

        return new AgentEventOutboxPayload.Inline(AGENT.read(json, kind.type(), "event payload"));
    }
}
