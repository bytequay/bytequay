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
package com.bytequay.app.web;

import com.bytequay.app.beans.footprints.FootprintsTrailDto;
import com.bytequay.app.service.footprints.FootprintsService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import static java.util.Objects.requireNonNull;

@RestController
public class FootprintsController
{
    private final FootprintsService service;

    public FootprintsController(FootprintsService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /**
     * GET /api/footprints?date=YYYY-MM-DD&zone=Area/City — the trail for
     * a calendar day. {@code date} defaults to today and {@code zone} to
     * the server's default zone.
     */
    @GetMapping("/api/footprints")
    public FootprintsTrailDto trail(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String zone)
    {
        ZoneId resolvedZone = parseZone(zone);
        LocalDate resolvedDate = parseDate(date, resolvedZone);
        return FootprintsTrailDto.from(service.trailForDay(resolvedDate, resolvedZone));
    }

    private static ZoneId parseZone(String raw)
    {
        if (raw == null || raw.trim().isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw.trim());
        }
        catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "invalid zone: " + raw);
        }
    }

    private static LocalDate parseDate(String raw, ZoneId zone)
    {
        if (raw == null || raw.trim().isEmpty()) {
            return LocalDate.now(zone);
        }
        try {
            return LocalDate.parse(raw.trim());
        }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "invalid date (want YYYY-MM-DD): " + raw);
        }
    }
}
