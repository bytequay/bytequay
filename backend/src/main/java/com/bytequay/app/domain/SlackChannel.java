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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A Slack channel as returned by {@code conversations.list} (member-of
 * filter). Used by the channel-selection screen to render the picker
 * rows. Fields:
 *
 * <ul>
 *   <li>{@code id} — Slack's stable channel id (Cxxxx public, Gxxxx private).</li>
 *   <li>{@code name} — display name without the leading {@code #}.</li>
 *   <li>{@code isPrivate} — true for groups (private channels) + group DMs.</li>
 *   <li>{@code memberCount} — exposed by Slack on the list response;
 *       null when the API didn't supply it (rare).</li>
 *   <li>{@code latestActivityAt} — timestamp of the most recent message
 *       in the channel; drives the smart-default sort (top 3 most
 *       active). Null when Slack has no {@code latest.ts} on the row.</li>
 * </ul>
 */
public record SlackChannel(
        String id,
        String name,
        boolean isPrivate,
        Integer memberCount,
        Instant latestActivityAt) {}
