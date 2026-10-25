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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One entry in an assistant conversation: the user's message, the assistant's reply, what an action left, a list or a draft the
 * assistant showed, or why a reply failed.
 *
 * @param text the user's words, the reply's Markdown, a receipt's line, a list's or a draft's title, or the failure's reason
 * @param visible a message's cards on screen when it was sent
 * @param onScreen a message's ref of the page the user was looking at
 * @param key a message's key from its sender, so sending it again adds nothing
 * @param targets where each ref a reply names opens; only refs the conversation served
 * @param notReady whether a failure is the model not being ready, which Settings fixes
 * @param receipt what a receipt's action was, and what it opens
 * @param list a list's style and refs, never its rows' text
 * @param draft a draft's repository and description
 */
public record AssistantItem(
        String id,
        String conversationId,
        Instant at,
        Kind kind,
        String text,
        List<String> visible,
        Optional<String> onScreen,
        Optional<String> key,
        Map<String, AgendaTarget> targets,
        boolean notReady,
        Optional<AssistantReceipt> receipt,
        Optional<AssistantList> list,
        Optional<AssistantDraft> draft)
{
    public enum Kind
    {
        USER, REPLY, RECEIPT, FAILURE, LIST, DRAFT
    }

    public AssistantItem
    {
        checkText(id, "id");
        checkText(conversationId, "conversationId");
        requireNonNull(at, "at is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(text, "text is null");
        visible = ImmutableList.copyOf(visible);
        requireNonNull(onScreen, "onScreen is null");
        requireNonNull(key, "key is null");
        targets = ImmutableMap.copyOf(targets);
        requireNonNull(receipt, "receipt is null");
        checkArgument(receipt.isPresent() == (kind == Kind.RECEIPT), "only a receipt carries its action");
        checkArgument(list.isPresent() == (kind == Kind.LIST), "only a list carries its refs");
        checkArgument(draft.isPresent() == (kind == Kind.DRAFT), "only a draft carries its description");
    }

    public static AssistantItem user(String conversationId, Instant at, String text, List<String> visible, Optional<String> onScreen, String key)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.USER, text, visible, onScreen, Optional.of(key), ImmutableMap.of(), false, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public static AssistantItem reply(String conversationId, Instant at, String text, Map<String, AgendaTarget> targets)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.REPLY, text, ImmutableList.of(), Optional.empty(), Optional.empty(), targets, false,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static AssistantItem receipt(String conversationId, Instant at, String text, AssistantReceipt receipt)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.RECEIPT, text, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of(),
                false, Optional.of(receipt), Optional.empty(), Optional.empty());
    }

    public static AssistantItem list(String conversationId, Instant at, String title, AssistantList list)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.LIST, title, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of(),
                false, Optional.empty(), Optional.of(list), Optional.empty());
    }

    public static AssistantItem draft(String conversationId, Instant at, String title, AssistantDraft draft)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.DRAFT, title, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of(),
                false, Optional.empty(), Optional.empty(), Optional.of(draft));
    }

    public static AssistantItem failure(String conversationId, Instant at, String reason, boolean notReady)
    {
        return new AssistantItem(UUID.randomUUID().toString(), conversationId, at, Kind.FAILURE, reason, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of(),
                notReady, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
