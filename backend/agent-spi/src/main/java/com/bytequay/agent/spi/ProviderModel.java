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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/**
 * One selectable model reported by a provider, identified by the stable value
 * the provider accepts on session start rather than a display name.
 */
public record ProviderModel(
        String id,
        String label,
        String description,
        List<String> efforts,
        Optional<String> defaultEffort,
        boolean isDefault,
        List<String> inputModalities,
        List<ProviderServiceTier> serviceTiers)
{
    public ProviderModel(
            String id, String label, String description, List<String> efforts, Optional<String> defaultEffort, boolean isDefault, List<String> inputModalities)
    {
        this(id, label, description, efforts, defaultEffort, isDefault, inputModalities, ImmutableList.of());
    }

    /** A text-only model, which is what every model was before input modalities were carried. */
    public ProviderModel(
            String id,
            String label,
            String description,
            List<String> efforts,
            Optional<String> defaultEffort,
            boolean isDefault)
    {
        this(id, label, description, efforts, defaultEffort, isDefault, ImmutableList.of("text"));
    }

    public ProviderModel
    {
        serviceTiers = ImmutableList.copyOf(serviceTiers);
        inputModalities = ImmutableList.copyOf(requireNonNull(inputModalities, "inputModalities is null"));
        checkNotBlank(id, "id");
        checkNotBlank(label, "label");
        requireNonNull(description, "description is null");
        efforts = ImmutableList.copyOf(requireNonNull(efforts, "efforts is null"));
        requireNonNull(defaultEffort, "defaultEffort is null");
    }
}
