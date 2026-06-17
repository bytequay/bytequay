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
package com.bytequay.app.repository.sqlite;

import java.time.Instant;

/**
 * Null-safe conversions between {@link Instant} and the epoch-millis
 * {@code long} columns the SQLite entities persist. Every store mapped
 * timestamps with the same null-guarded {@code toEpochMilli} /
 * {@code ofEpochMilli} ternary inline; centralising it keeps the null
 * handling consistent and the mappers readable.
 */
final class Timestamps
{
    private Timestamps() {}

    /** Epoch-millis for persistence; a {@code null} {@link Instant} stays null. */
    static Long epochMilli(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    /** {@link Instant} from a persisted epoch-millis column; null stays null. */
    static Instant instant(Long epochMilli)
    {
        return epochMilli == null ? null : Instant.ofEpochMilli(epochMilli);
    }
}
