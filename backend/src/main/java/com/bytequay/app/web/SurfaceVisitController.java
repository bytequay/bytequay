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

import com.bytequay.app.beans.footprints.RecordVisitRequest;
import com.bytequay.app.beans.footprints.SurfaceVisitDto;
import com.bytequay.app.domain.SurfaceType;
import com.bytequay.app.domain.SurfaceVisit;
import com.bytequay.app.service.footprints.FootprintsService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

@RestController
public class SurfaceVisitController
{
    private final FootprintsService service;

    public SurfaceVisitController(FootprintsService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /** POST /api/footprints/visit — records one visit to a tracked surface. */
    @PostMapping("/api/footprints/visit")
    public SurfaceVisitDto record(@RequestBody RecordVisitRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (isBlank(body.surfaceId())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "surfaceId is required");
        }
        SurfaceType type = parseType(body.surfaceType());
        SurfaceVisit visit = service.recordVisit(type, body.surfaceId().trim(), body.title(), body.context());
        return SurfaceVisitDto.from(visit);
    }

    private static SurfaceType parseType(String raw)
    {
        if (isBlank(raw)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "surfaceType is required");
        }
        try {
            return SurfaceType.valueOf(raw.trim());
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "unknown surfaceType: " + raw);
        }
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}
