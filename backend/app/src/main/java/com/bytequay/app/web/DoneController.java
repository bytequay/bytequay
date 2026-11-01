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

import com.bytequay.app.service.done.DoneService;
import com.bytequay.core.domain.DoneActivity;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class DoneController
{
    private final DoneService done;

    public DoneController(DoneService done)
    {
        this.done = requireNonNull(done, "done is null");
    }

    @GetMapping("/done")
    public DoneActivity done(@RequestParam String from, @RequestParam String to)
    {
        LocalDate first = day(from);
        LocalDate last = day(to);
        if (last.isBefore(first) || !first.plusDays(DoneService.MAXIMUM_DAYS).isAfter(last)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Choose up to " + DoneService.MAXIMUM_DAYS + " days, from before to");
        }
        return done.done(first, last);
    }

    private static LocalDate day(String value)
    {
        try {
            return LocalDate.parse(value);
        }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Days are yyyy-MM-dd");
        }
    }
}
