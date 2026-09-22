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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;

class TestJsonColumns
{
    /**
     * A background thread in the packaged jar can carry a context class loader that reaches no
     * service file, which is how a stored CI Autofix attempt once became unwritable.
     */
    @Test
    void writesOptionalAndInstantWithoutADiscoverableModule()
            throws Exception
    {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
        try {
            ObjectMapper mapper = JsonColumns.mapper().disable(WRITE_DATES_AS_TIMESTAMPS);
            assertThat(mapper.writeValueAsString(Optional.of("value"))).isEqualTo("\"value\"");
            assertThat(mapper.writeValueAsString(Optional.empty())).isEqualTo("null");
            assertThat(mapper.writeValueAsString(Instant.parse("2026-09-21T17:20:16Z"))).isEqualTo("\"2026-09-21T17:20:16Z\"");
        }
        finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
