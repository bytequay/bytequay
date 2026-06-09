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

import com.bytequay.app.service.workmodel.WorkModelCatalog.CatalogEntry;
import com.bytequay.app.service.workmodel.WorkModelCatalog.CatalogProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the catalog shape the ds4 work pivots around: the legacy
 * "local" provider is gone, deepseek carries three entries, and the
 * deepseek-v4-flash model is marked {@code localServed}.
 */
class TestWorkModelCatalogDs4
{
    @Test
    void apiProvidersDoNotIncludeTheLegacyLocalProviderAnyMore()
    {
        List<String> ids = WorkModelCatalog.API_PROVIDERS.stream()
                .map(CatalogProvider::id)
                .toList();
        assertThat(ids).doesNotContain("local");
        assertThat(ids).contains("anthropic", "openai", "deepseek");
    }

    @Test
    void deepseekProviderListsThreeModelsIncludingTheLocallyServedV4Flash()
    {
        CatalogProvider deepseek = WorkModelCatalog.API_PROVIDERS.stream()
                .filter(p -> "deepseek".equals(p.id()))
                .findFirst()
                .orElseThrow();
        List<String> modelIds = deepseek.models().stream().map(CatalogEntry::id).toList();
        assertThat(modelIds).containsExactly(
                "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash");
    }

    @Test
    void v4FlashIsTheOnlyLocallyServedEntryInTheWholeCatalog()
    {
        List<CatalogEntry> localEntries = WorkModelCatalog.API_PROVIDERS.stream()
                .flatMap(p -> p.models().stream())
                .filter(CatalogEntry::localServed)
                .toList();
        assertThat(localEntries).extracting(CatalogEntry::id).containsExactly("deepseek-v4-flash");
    }

    @Test
    void existingCatalogEntriesKeepTheirOldBehaviourThroughTheCompatConstructor()
    {
        // The compat ctor (id, displayName, isDefault) defaults
        // localServed=false. Every entry that was on the catalog
        // before the ds4 refactor lands here with localServed=false.
        long cloudCount = WorkModelCatalog.API_PROVIDERS.stream()
                .flatMap(p -> p.models().stream())
                .filter(e -> !e.localServed())
                .count();
        assertThat(cloudCount).isGreaterThan(0);
    }
}
