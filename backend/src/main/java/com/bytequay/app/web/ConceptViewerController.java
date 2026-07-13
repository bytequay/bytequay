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

import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptSpec;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Read-only REST surface for the Settings → Concepts page. The
 * page renders the concept catalog (kind nav + search + rows);
 * authoring lives on the {@code @Concept} annotation in code or
 * inside the brain glossary, never through this endpoint.
 *
 * <p>This controller is intentionally <em>not</em> the same path
 * the agent's {@code list_terms} tool uses — agents read the
 * registry via the tool axis, humans read it via this endpoint.
 * Keeping them split makes the agent-non-callable contract for
 * the inspector / catalog explicit.
 */
@RestController
@RequestMapping("/api/concepts")
public class ConceptViewerController
{
    /** Wire shape for one row in the catalog. */
    public record ConceptRow(
            String name,
            String kind,
            String definition,
            List<String> aka,
            List<String> sources,
            List<String> relatedTools,
            List<String> relatedConcepts,
            String scope) {}

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final ConceptRegistry registry;

    public ConceptViewerController(ConceptRegistry registry)
    {
        this.registry = requireNonNull(registry, "registry is null");
    }

    @GetMapping
    public List<ConceptRow> list(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "limit", required = false) Integer limit)
    {
        ConceptKind kindFilter = parseKind(kind);
        if (kind != null && !kind.isBlank() && kindFilter == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "unknown kind: " + kind + " (expected NOUN/STATE/FILTER/VERB)");
        }
        int cap = limit == null
                ? DEFAULT_LIMIT
                : Math.clamp(limit, 1, MAX_LIMIT);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        List<ConceptSpec> all = registry.list(kindFilter);
        List<ConceptRow> rows = new ArrayList<>();
        for (ConceptSpec spec : all) {
            if (!needle.isEmpty() && !matches(spec, needle)) {
                continue;
            }
            rows.add(toRow(spec));
            if (rows.size() >= cap) {
                break;
            }
        }
        return rows;
    }

    private static boolean matches(ConceptSpec spec, String needle)
    {
        if (spec.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String alias : spec.aka()) {
            if (alias.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return spec.definition() != null
                && spec.definition().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ConceptRow toRow(ConceptSpec spec)
    {
        return new ConceptRow(
                spec.name(),
                spec.kind().name(),
                spec.definition(),
                spec.aka(),
                // v1 has no per-paragraph provenance; surface the
                // source URI (e.g. fully-qualified type name) as the
                // single audit anchor.
                spec.source() == null ? List.of() : List.of(spec.source()),
                spec.relatedTools(),
                spec.relatedConcepts(),
                spec.scope().name());
    }

    private static ConceptKind parseKind(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ConceptKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
