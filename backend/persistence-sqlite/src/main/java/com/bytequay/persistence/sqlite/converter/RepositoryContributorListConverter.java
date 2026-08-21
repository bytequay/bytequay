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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.core.domain.RepositoryContributor;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class RepositoryContributorListConverter
        extends JsonColumnConverter<List<RepositoryContributor>>
{
    RepositoryContributorListConverter()
    {
        super(new TypeReference<>() {}, "repository contributor list", "[]", List.of());
    }

    @Override
    protected boolean isEmpty(List<RepositoryContributor> value)
    {
        return value.isEmpty();
    }
}
