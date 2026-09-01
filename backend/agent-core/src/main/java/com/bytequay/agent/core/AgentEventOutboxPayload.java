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
package com.bytequay.agent.core;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Bounded event body retained until its outbox row is appended to the task log. */
public sealed interface AgentEventOutboxPayload
        permits AgentEventOutboxPayload.Inline, AgentEventOutboxPayload.UserMessageBlob
{
    AgentEventPayload.Kind kind();

    record Inline(AgentEventPayload payload)
            implements AgentEventOutboxPayload
    {
        public Inline
        {
            requireNonNull(payload, "payload is null");
            checkArgument(payload.kind() != AgentEventPayload.Kind.USER_MESSAGE,
                    "user messages must use a blob-backed outbox payload");
        }

        @Override
        public AgentEventPayload.Kind kind()
        {
            return payload.kind();
        }
    }

    /** Keeps message bytes outside SQLite while retaining enough data to materialize the event. */
    record UserMessageBlob(
            BlobRef textBlob,
            List<AgentAttachmentReference> attachments,
            AgentEventPayload.DeliveryKind deliveryKind)
            implements AgentEventOutboxPayload
    {
        public UserMessageBlob
        {
            requireNonNull(textBlob, "textBlob is null");
            attachments = ImmutableList.copyOf(attachments);
            requireNonNull(deliveryKind, "deliveryKind is null");
        }

        @Override
        public AgentEventPayload.Kind kind()
        {
            return AgentEventPayload.Kind.USER_MESSAGE;
        }
    }
}
