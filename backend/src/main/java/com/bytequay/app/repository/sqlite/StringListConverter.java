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
import com.google.common.collect.ImmutableList;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * JPA attribute converter that serializes {@code List<String>} to a JSON text column
 * and deserializes it back. Applied automatically to all {@code List<String>} fields
 * annotated with {@code @Convert(converter = StringListConverter.class)}.
 */
@Converter
class StringListConverter
        extends JsonColumnConverter<List<String>>
{
    StringListConverter()
    {
        super(new TypeReference<>() {}, "string list", "[]", ImmutableList.of());
    }

    @Override
    protected boolean isEmpty(List<String> value)
    {
        return value.isEmpty();
    }
}
