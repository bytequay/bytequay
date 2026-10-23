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
package com.bytequay.app.service.settings;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One agent type's resolved model and effort.
 *
 * @param explicit false when nothing is stored at the level that was asked and
 * the value came from the level below: Review inheriting Development globally,
 * or a workspace inheriting the global value. Inheritance is always an absent
 * row, never a copied one, so this is what tells a settings row whether to
 * offer pickers or a "use global" badge.
 */
public record AgentModelSetting(AgentModelType type, boolean explicit, String configId, String label, String model, String modelLabel, Optional<String> effort)
{
    public AgentModelSetting
    {
        requireNonNull(type, "type is null");
        requireNonNull(configId, "configId is null");
        requireNonNull(label, "label is null");
        requireNonNull(model, "model is null");
        requireNonNull(modelLabel, "modelLabel is null");
        requireNonNull(effort, "effort is null");
    }
}
