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
package com.bytequay.app.service.workmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexModelCatalogProbe
{
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesPickerMetadataAndFiltersHiddenModels()
            throws Exception
    {
        var result = json.readTree("""
                {
                  "data": [
                    {
                      "id": "gpt-5.6-sol",
                      "model": "gpt-5.6-sol",
                      "displayName": "GPT-5.6 Sol",
                      "description": "Frontier coding model",
                      "hidden": false,
                      "isDefault": true,
                      "defaultReasoningEffort": "low",
                      "supportedReasoningEfforts": [
                        {"reasoningEffort": "low", "description": "Fast"},
                        {"reasoningEffort": "high", "description": "Deeper reasoning"}
                      ]
                    },
                    {
                      "id": "internal-model",
                      "displayName": "Internal",
                      "hidden": true
                    }
                  ],
                  "nextCursor": "page-2"
                }
                """);

        CodexModelCatalogProbe.Page page = CodexModelCatalogProbe.parsePage(result);

        assertThat(page.nextCursor()).isEqualTo("page-2");
        assertThat(page.models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("gpt-5.6-sol");
            assertThat(model.displayName()).isEqualTo("GPT-5.6 Sol");
            assertThat(model.description()).isEqualTo("Frontier coding model");
            assertThat(model.isDefault()).isTrue();
            assertThat(model.defaultReasoningEffort()).isEqualTo("low");
            assertThat(model.supportedReasoningEfforts())
                    .extracting(CodexModelCatalogProbe.ReasoningEffort::id)
                    .containsExactly("low", "high");
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "codex.integration", matches = "true")
    void readsTheInstalledCodexCliCatalog()
    {
        var models = new CodexModelCatalogProbe(json).models(true).orElseThrow();

        assertThat(models).isNotEmpty();
        assertThat(models).anySatisfy(model -> assertThat(model.isDefault()).isTrue());
        assertThat(models).allSatisfy(model -> {
            assertThat(model.id()).isNotBlank();
            assertThat(model.displayName()).isNotBlank();
            assertThat(model.supportedReasoningEfforts()).isNotEmpty();
        });
    }
}
