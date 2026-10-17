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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * One entry in a day's assistant conversation: the user's message, the assistant's reply, or why a reply failed.
 *
 * @param text the user's words, the reply's Markdown, or the failure's reason
 * @param visible a message's cards on screen when it was sent
 * @param onScreen a message's ref of the page the user was looking at
 * @param key a message's key from its sender, so sending it again adds nothing
 * @param targets where each ref a reply names opens; only refs the day served
 * @param notReady whether a failure is the model not being ready, which Settings fixes
 */
public record AssistantItem(
        String id,
        LocalDate day,
        Instant at,
        Kind kind,
        String text,
        List<String> visible,
        Optional<String> onScreen,
        Optional<String> key,
        Map<String, AgendaTarget> targets,
        boolean notReady)
{
    public enum Kind
    {
        USER, REPLY, FAILURE
    }

    public AssistantItem
    {
        checkText(id, "id");
        requireNonNull(day, "day is null");
        requireNonNull(at, "at is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(text, "text is null");
        visible = ImmutableList.copyOf(visible);
        requireNonNull(onScreen, "onScreen is null");
        requireNonNull(key, "key is null");
        targets = ImmutableMap.copyOf(targets);
    }

    public static AssistantItem user(LocalDate day, Instant at, String text, List<String> visible, Optional<String> onScreen, String key)
    {
        return new AssistantItem(UUID.randomUUID().toString(), day, at, Kind.USER, text, visible, onScreen, Optional.of(key), ImmutableMap.of(), false);
    }

    public static AssistantItem reply(LocalDate day, Instant at, String text, Map<String, AgendaTarget> targets)
    {
        return new AssistantItem(UUID.randomUUID().toString(), day, at, Kind.REPLY, text, ImmutableList.of(), Optional.empty(), Optional.empty(), targets, false);
    }

    public static AssistantItem failure(LocalDate day, Instant at, String reason, boolean notReady)
    {
        return new AssistantItem(UUID.randomUUID().toString(), day, at, Kind.FAILURE, reason, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of(), notReady);
    }
}
