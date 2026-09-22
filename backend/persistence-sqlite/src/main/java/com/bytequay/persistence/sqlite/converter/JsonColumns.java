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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Every JSON column's mapper, so stored values cannot depend on which thread first loaded a codec. */
public final class JsonColumns
{
    private JsonColumns() {}

    /**
     * One mapper for a JSON column, configurable further by its caller.
     *
     * <p>The modules are named rather than discovered: {@code findAndRegisterModules} resolves
     * them through the context class loader of whichever thread first loads the calling class,
     * and in the packaged jar a background thread can carry a loader that sees neither datatype.
     * A mapper built there rejects every {@code Optional} and {@code Instant} it is asked to write.
     */
    public static ObjectMapper mapper()
    {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());
    }
}
