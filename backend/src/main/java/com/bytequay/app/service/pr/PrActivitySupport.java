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
package com.bytequay.app.service.pr;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class PrActivitySupport
{
    private static final int DAILY_MAX_DAYS_ALL = 90;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private PrActivitySupport() {}

    static String normalizeScope(String raw)
    {
        if (raw == null) {
            return "30d";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "7d", "30d", "90d", "all" -> normalized;
            default -> "30d";
        };
    }

    static Instant cutoffFor(String scope)
    {
        Instant now = Instant.now();
        return switch (scope) {
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            case "90d" -> now.minus(Duration.ofDays(90));
            default -> Instant.EPOCH;
        };
    }

    static ZoneId resolveZone(String requested)
    {
        if (requested == null || requested.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(requested);
        }
        catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    static boolean isAllTime(Instant cutoff)
    {
        return Instant.EPOCH.equals(cutoff);
    }

    static LocalDate dailyStartDate(Instant cutoff, ZoneId zone, LocalDate today)
    {
        return isAllTime(cutoff)
                ? today.minusDays(DAILY_MAX_DAYS_ALL - 1L)
                : cutoff.atZone(zone).toLocalDate();
    }

    static String formatDate(LocalDate date)
    {
        return date.format(ISO_DATE);
    }

    static String formatCount(long n)
    {
        if (n < 1000) {
            return Long.toString(n);
        }
        return String.format(Locale.ROOT, "%,d", n);
    }
}
