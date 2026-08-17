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
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class CiAutofixJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<WarningValue>> WARNING_VALUES = new TypeReference<>() {};
    private static final TypeReference<List<FailureValue>> FAILURE_VALUES = new TypeReference<>() {};

    private CiAutofixJson() {}

    public static String encodeWarnings(List<Warning> warnings)
    {
        return encode(warnings.stream()
                .map(warning -> new WarningValue(
                        warning.code(),
                        warning.message(),
                        warning.occurredAt().toString()))
                .collect(toImmutableList()), "CI Autofix warnings");
    }

    public static List<Warning> decodeWarnings(String json)
    {
        if (json == null || json.isBlank()) {
            return ImmutableList.of();
        }
        try {
            return MAPPER.readValue(json, WARNING_VALUES).stream()
                    .map(value -> new Warning(value.code(), value.message(), Instant.parse(value.occurredAt())))
                    .collect(toImmutableList());
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw new CiAutofixStorageException("Cannot deserialize CI Autofix warnings", e);
        }
    }

    public static String encodeFailures(List<FailureEvidence> failures)
    {
        return encode(failures.stream()
                .map(failure -> new FailureValue(
                        failure.signature(),
                        failure.summary(),
                        failure.workflowRunId(),
                        failure.workflowAttempt(),
                        failure.jobId(),
                        failure.jobAttempt(),
                        failure.log().map(log -> new LogValue(
                                log.path(),
                                log.mediaType(),
                                log.size(),
                                log.truncated())).orElse(null)))
                .collect(toImmutableList()), "CI Autofix failures");
    }

    public static List<FailureEvidence> decodeFailures(String json)
    {
        try {
            return MAPPER.readValue(json, FAILURE_VALUES).stream()
                    .map(value -> new FailureEvidence(
                            value.signature(),
                            value.summary(),
                            value.workflowRunId(),
                            value.workflowAttempt(),
                            value.jobId(),
                            value.jobAttempt(),
                            Optional.ofNullable(value.log()).map(log -> new LogReference(
                                    log.path(),
                                    log.mediaType(),
                                    log.size(),
                                    log.truncated()))))
                    .collect(toImmutableList());
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw new CiAutofixStorageException("Cannot deserialize CI Autofix failures", e);
        }
    }

    private static String encode(Object value, String label)
    {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new CiAutofixStorageException("Cannot serialize " + label, e);
        }
    }

    private record WarningValue(String code, String message, String occurredAt) {}

    private record FailureValue(
            String signature,
            String summary,
            long workflowRunId,
            int workflowAttempt,
            long jobId,
            int jobAttempt,
            LogValue log) {}

    private record LogValue(String path, String mediaType, int size, boolean truncated) {}
}
