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
package com.bytequay.app.service.concepts;

import com.bytequay.app.repository.UserConceptStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of {@link SavedViewsService} against the
 * Flyway-migrated {@code concept_user} table. Covers the round-trip
 * shape + the registry sync that {@code list_terms} relies on.
 */
@SpringBootTest
class TestSavedViewsService
{
    @Autowired
    private SavedViewsService savedViews;

    @Autowired
    private ConceptRegistry registry;

    @Test
    void saveRegistersUserScopedSpec()
    {
        String name = "shippable-" + Math.abs(UUID.randomUUID().hashCode());
        savedViews.save(name, ConceptKind.FILTER,
                "A PR that's ready to merge — green CI, no unresolved threads.",
                List.of("ready"),
                null);
        try {
            ConceptSpec spec = registry.byName(name).orElseThrow();
            assertThat(spec.scope()).isEqualTo(ConceptScope.USER);
            assertThat(spec.kind()).isEqualTo(ConceptKind.FILTER);
            assertThat(spec.aka()).containsExactly("ready");
            assertThat(spec.source()).startsWith("user://saved-views/");
        }
        finally {
            savedViews.delete(name);
        }
    }

    @Test
    void saveIsUpsert()
    {
        String name = "snoozeable-" + Math.abs(UUID.randomUUID().hashCode());
        savedViews.save(name, ConceptKind.FILTER, "First take.", List.of(), null);
        UserConceptStore.UserConceptRow updated = savedViews.save(
                name, ConceptKind.FILTER, "Revised definition.", List.of("snooze-candidate"), null);
        try {
            assertThat(updated.definition()).isEqualTo("Revised definition.");
            assertThat(registry.byName(name).orElseThrow().aka()).containsExactly("snooze-candidate");
        }
        finally {
            savedViews.delete(name);
        }
    }

    @Test
    void deleteDropsFromRegistry()
    {
        String name = "deletable-" + Math.abs(UUID.randomUUID().hashCode());
        savedViews.save(name, ConceptKind.FILTER, "Whatever.", List.of(), null);
        assertThat(registry.byName(name)).isPresent();

        savedViews.delete(name);

        assertThat(registry.byName(name)).isEmpty();
    }

    @Test
    void invalidNameIsRejected()
    {
        assertThatThrownBy(() -> savedViews.save(
                "Bad Name With Spaces", ConceptKind.FILTER, "x", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("name must match");
    }

    @Test
    void blankDefinitionIsRejected()
    {
        String name = "blankdef-" + Math.abs(UUID.randomUUID().hashCode());
        assertThatThrownBy(() -> savedViews.save(name, ConceptKind.FILTER, "   ", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("definition is required");
    }
}
