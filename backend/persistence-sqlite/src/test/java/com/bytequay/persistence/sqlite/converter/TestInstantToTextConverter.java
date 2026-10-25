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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestInstantToTextConverter
{
    @Test
    void sqliteOrdersAndFiltersMixedPrecisionInstantsChronologically()
            throws SQLException
    {
        InstantToTextConverter converter = new InstantToTextConverter();
        Instant second = Instant.parse("2026-08-18T00:00:00Z");
        List<Instant> ordered = ImmutableList.of(
                second,
                second.plusNanos(1),
                second.plusNanos(1000),
                second.plusMillis(1),
                second.plusMillis(123),
                second.plusNanos(123_000_001),
                second.plusNanos(123_001_000),
                second.plusNanos(999_999_999),
                second.plusSeconds(1));
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();

        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                var schema = connection.createStatement()) {
            schema.executeUpdate("CREATE TABLE events (stored_at TEXT NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO events (stored_at) VALUES (?)")) {
                for (Instant instant : ordered.reversed()) {
                    insert.setString(1, converter.convertToDatabaseColumn(instant));
                    insert.executeUpdate();
                }
            }
            try (var all = connection.prepareStatement("SELECT stored_at FROM events ORDER BY stored_at");
                    var range = connection.prepareStatement("SELECT stored_at FROM events WHERE stored_at >= ? AND stored_at < ? ORDER BY stored_at")) {
                assertThat(read(all, converter)).containsExactlyElementsOf(ordered);
                for (int index = 0; index < ordered.size(); index++) {
                    range.setString(1, converter.convertToDatabaseColumn(ordered.get(index)));
                    range.setString(2, converter.convertToDatabaseColumn(second.plusSeconds(1)));
                    assertThat(read(range, converter)).as("range starting at %s", ordered.get(index))
                            .containsExactlyElementsOf(ordered.subList(index, ordered.size() - 1));
                }
            }
        }
    }

    private static List<Instant> read(PreparedStatement query, InstantToTextConverter converter)
            throws SQLException
    {
        ImmutableList.Builder<Instant> result = ImmutableList.builder();
        try (var rows = query.executeQuery()) {
            while (rows.next()) {
                result.add(converter.convertToEntityAttribute(rows.getString(1)));
            }
        }
        return result.build();
    }
}
