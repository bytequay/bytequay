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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.CI_AUTOFIX;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

final class CiAutofixJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().disable(WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<List<Warning>> WARNING_VALUES = new TypeReference<>() {};
    private static final TypeReference<List<FailureEvidence>> FAILURE_VALUES = new TypeReference<>() {};

    private CiAutofixJson() {}

    public static String encodeWarnings(List<Warning> warnings)
    {
        return encode(ImmutableList.copyOf(warnings), "CI Autofix warnings");
    }

    public static List<Warning> decodeWarnings(String json)
    {
        if (json == null || json.isBlank()) {
            return ImmutableList.of();
        }
        return CI_AUTOFIX.guard("Cannot deserialize CI Autofix warnings", () -> ImmutableList.copyOf(MAPPER.readValue(json, WARNING_VALUES)));
    }

    public static String encodeFailures(List<FailureEvidence> failures)
    {
        return encode(ImmutableList.copyOf(failures), "CI Autofix failures");
    }

    public static List<FailureEvidence> decodeFailures(String json)
    {
        return CI_AUTOFIX.guard("Cannot deserialize CI Autofix failures", () -> ImmutableList.copyOf(MAPPER.readValue(json, FAILURE_VALUES)));
    }

    private static String encode(Object value, String label)
    {
        return CI_AUTOFIX.guard("Cannot serialize " + label, () -> MAPPER.writeValueAsString(value));
    }
}
