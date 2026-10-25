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
package com.bytequay.persistence.sqlite.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
/**
 * Stores {@link Instant} values as ISO-8601 text in SQLite.
 *
 * <p>Hibernate 6 maps Instant through TimestampUtcAsJdbcTimestampJdbcType, which calls
 * setTimestamp() on SQLite-JDBC. That driver stores the value as raw epoch milliseconds
 * (a long integer), then getTimestamp() fails to parse it back as a date string.
 * This converter bypasses that path by going through getString()/setString() instead.
 * Fields with {@code disableConversion = true} retain their existing native timestamp columns.
 */
@Converter(autoApply = true)
public class InstantToTextConverter
        implements AttributeConverter<Instant, String>
{
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    @Override
    public String convertToDatabaseColumn(Instant attribute)
    {
        return attribute == null ? null : format(attribute);
    }

    /** SQLite orders these columns as text, so fractional seconds must have the same width. */
    public static String format(Instant value)
    {
        return FORMAT.format(value);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData)
    {
        return dbData == null ? null : Instant.parse(dbData);
    }
}
