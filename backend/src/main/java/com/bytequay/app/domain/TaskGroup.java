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
 * One user-defined grouping of {@link Task}s — a "project bucket"
 * like "Trino refactor" or "ByteQuay backend". Tasks can be
 * unassigned ({@code Task.groupId} is null); a group can exist with
 * zero tasks (it just won't appear in derived counts).
 *
 * <p>The frontend renders {@link #glyph} inside a colored badge in
 * the left rail. Both fields are free-form so users can pick any
 * emoji and any CSS color string without a migration.
 */
public record TaskGroup(
        String id,
        String name,
        String glyph,
        String color,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt)
{
}
