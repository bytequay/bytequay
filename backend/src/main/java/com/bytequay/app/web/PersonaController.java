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

import com.bytequay.app.beans.personas.PersonaRequest;
import com.bytequay.app.beans.personas.ReviewerPersonaDto;
import com.bytequay.app.service.personas.PersonaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for user-defined reviewer personas. Thin delegator over
 * {@link PersonaService}; all validation, persistence, and conversion
 * lives in the service impl.
 */
@RestController
@RequestMapping("/api/personas")
public class PersonaController
{
    private final PersonaService personas;

    public PersonaController(PersonaService personas)
    {
        this.personas = requireNonNull(personas, "personas is null");
    }

    @GetMapping
    public List<ReviewerPersonaDto> list()
    {
        return personas.listActive();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewerPersonaDto> get(@PathVariable String id)
    {
        return personas.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewerPersonaDto create(@RequestBody PersonaRequest body)
    {
        try {
            return personas.create(body);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ReviewerPersonaDto update(@PathVariable String id, @RequestBody PersonaRequest body)
    {
        try {
            return personas.update(id, body);
        }
        catch (IllegalArgumentException e) {
            // Distinguish 404 (not found) from 400 (bad request body) by
            // the message prefix the service produces.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatusCode status = msg.startsWith("persona not found")
                    ? HttpStatusCode.valueOf(404)
                    : HttpStatusCode.valueOf(400);
            throw new ResponseStatusException(status, msg);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id)
    {
        personas.softDelete(id);
    }
}
