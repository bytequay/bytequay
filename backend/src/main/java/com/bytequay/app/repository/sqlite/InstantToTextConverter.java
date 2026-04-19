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

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Stores {@link Instant} values as ISO-8601 text in SQLite.
 *
 * <p>Hibernate 6 maps Instant through TimestampUtcAsJdbcTimestampJdbcType, which calls
 * setTimestamp() on SQLite-JDBC. That driver stores the value as raw epoch milliseconds
 * (a long integer), then getTimestamp() fails to parse it back as a date string.
 * This converter bypasses that path by going through getString()/setString() instead.
 *
 * <p>The reader handles three formats found in existing rows:
 * <ol>
 *   <li>ISO-8601 ("2026-04-21T10:30:00Z") – written by this converter</li>
 *   <li>SQLite CURRENT_TIMESTAMP ("2026-04-21 10:30:00") – written by Flyway seeds</li>
 *   <li>Epoch millis as string ("1776764879190") – written by pre-fix Hibernate</li>
 * </ol>
 */
@Converter
class InstantToTextConverter
        implements AttributeConverter<Instant, String>
{
    private static final DateTimeFormatter SQLITE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String convertToDatabaseColumn(Instant attribute)
    {
        if (attribute == null) {
            return null;
        }
        return attribute.toString();
    }

    static Instant parse(String dbData)
    {
        return new InstantToTextConverter().convertToEntityAttribute(dbData);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData)
    {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(dbData);
        }
        catch (Exception ignored) {
        }

        try {
            return LocalDateTime.parse(dbData, SQLITE_DATETIME).toInstant(ZoneOffset.UTC);
        }
        catch (Exception ignored) {
        }

        try {
            return Instant.ofEpochMilli(Long.parseLong(dbData));
        }
        catch (Exception ignored) {
        }

        return null;
    }
}
