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
package com.bytequay.core.domain;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One stored model-provider API key, identified by its user-chosen alias.
 * The secret itself never travels on this record; read it through the store.
 */
public record ModelApiKey(String alias, String provider, Instant createdAt)
{
    public ModelApiKey
    {
        requireNonNull(alias, "alias is null");
        checkArgument(!alias.isBlank(), "alias is blank");
        requireNonNull(provider, "provider is null");
        checkArgument(!provider.isBlank(), "provider is blank");
        requireNonNull(createdAt, "createdAt is null");
    }
}
