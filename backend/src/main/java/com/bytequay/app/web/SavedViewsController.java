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

import com.bytequay.app.repository.UserConceptStore;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.concepts.SavedViewsService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Saved Views settings page. CRUD over the
 * {@code concept_user} table; each mutation hands off to
 * {@link SavedViewsService} which keeps the {@link
 * com.bytequay.app.service.concepts.ConceptRegistry} in sync.
 */
@RestController
@RequestMapping("/api/concepts/user")
public class SavedViewsController
{
    private final SavedViewsService service;

    public SavedViewsController(SavedViewsService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /** Wire shape — matches {@link UserConceptStore.UserConceptRow}
     *  plus a stringified {@code kind} so the frontend doesn't have
     *  to know the enum's exact JSON encoding. */
    public record SavedViewDto(
            String name,
            String kind,
            String definition,
            List<String> aka,
            String criteriaJson,
            long createdAtMs,
            long updatedAtMs) {}

    /** Request body for create / update. */
    public record SavedViewBody(
            String name,
            String kind,
            String definition,
            List<String> aka,
            String criteriaJson) {}

    @GetMapping
    public List<SavedViewDto> list()
    {
        return service.list().stream().map(SavedViewsController::toDto).toList();
    }

    @PostMapping
    public SavedViewDto create(@RequestBody SavedViewBody body)
    {
        body = requireBody(body);
        ConceptKind kind;
        try {
            kind = ConceptKind.valueOf(
                    body.kind() == null ? "FILTER" : body.kind().trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "unknown kind: " + body.kind());
        }
        UserConceptStore.UserConceptRow row = service.save(
                body.name(),
                kind,
                body.definition(),
                body.aka() == null ? List.of() : body.aka(),
                body.criteriaJson());
        return toDto(row);
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name)
    {
        service.delete(name);
    }

    private static SavedViewDto toDto(UserConceptStore.UserConceptRow row)
    {
        return new SavedViewDto(
                row.name(),
                row.kind().name(),
                row.definition(),
                row.aka(),
                row.criteriaJson(),
                row.createdAtMs(),
                row.updatedAtMs());
    }
}
