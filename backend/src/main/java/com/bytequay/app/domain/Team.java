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
import java.util.Set;

/**
 * A local-only group of GitHub logins. The Kanban view for a team filters
 * the user's watched PRs down to those whose author is in {@link #members}.
 * Teams are not synced to or from GitHub — they're a pure ByteQuay concept
 * for organising who you care about.
 *
 * <p>Members are modelled as a {@link Set} since rosters are an unordered
 * collection of unique logins; duplicate handling stays in the persistence
 * layer rather than leaking into callers.
 */
public record Team(
        long id,
        String name,
        String avatar,
        String color,
        /** Optional one-line description, e.g. "Building Trino's query
         *  engine and connectors". Surfaced in the team sidebar card
         *  and inside the New Team modal's live preview. Null when
         *  the user didn't supply one. */
        String description,
        Set<String> members,
        Instant createdAt,
        Instant updatedAt) {}
