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
package com.bytequay.app.repository.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * JPA attribute converter that serializes {@code Map<String, Long>} to
 * a JSON text column. Used for the {@code repo_meta.languages} column
 * (language name → byte count, from GitHub's {@code /languages}).
 */
@Converter
class StringLongMapConverter
        extends JsonColumnConverter<Map<String, Long>>
{
    StringLongMapConverter()
    {
        super(new TypeReference<>() {}, "string-long map", "{}", ImmutableMap.of());
    }

    @Override
    protected boolean isEmpty(Map<String, Long> value)
    {
        return value.isEmpty();
    }
}
