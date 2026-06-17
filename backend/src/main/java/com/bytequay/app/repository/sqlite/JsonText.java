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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON read/write for the small list/map columns several SQLite stores
 * persist. Each store had its own {@code writeX} / {@code readX} pair
 * wrapping the same {@link ObjectMapper} try/catch; this centralises the
 * wrapping while leaving the per-column choices (null/empty guards,
 * fallback values) at the call site.
 *
 * <p>Two read policies are offered because the stores genuinely differ:
 * {@link #read} treats a parse failure as a programming/data error and
 * surfaces it, while {@link #readOrDefault} swallows it and falls back so
 * a single hand-mangled column can't 500 a list endpoint.
 */
final class JsonText
{
    private JsonText() {}

    /**
     * Serialise {@code value}. A failure means the value isn't
     * mapper-friendly — a programming error — so it surfaces as
     * {@link IllegalStateException} carrying {@code what}.
     */
    static String write(ObjectMapper mapper, Object value, String what)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(what, e);
        }
    }

    /**
     * Read {@code json} into {@code type}. Null / blank JSON yields
     * {@code fallback}; a parse failure surfaces as
     * {@link IllegalStateException} carrying {@code what}.
     */
    static <T> T read(ObjectMapper mapper, String json, TypeReference<T> type, T fallback, String what)
    {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(what, e);
        }
    }

    /**
     * Read {@code json} into {@code type}, falling back to {@code fallback}
     * for null / blank JSON <em>and</em> for any parse failure. Lenient by
     * design: a hand-truncated column degrades to the default instead of
     * failing the read path.
     */
    static <T> T readOrDefault(ObjectMapper mapper, String json, TypeReference<T> type, T fallback)
    {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            return fallback;
        }
    }
}
