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

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.core.cherrypick.CherryPickStorageException;
import com.bytequay.core.ci.CiAutofixStorageException;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.BiFunction;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static java.util.Objects.requireNonNull;

/**
 * Runs one Jackson call for a JSON column and reports its failure as the owning module's storage
 * exception, so a corrupt column surfaces as a storage error rather than a parser or validation exception.
 */
final class JsonGuard<E extends RuntimeException>
{
    static final JsonGuard<AgentStorageException> AGENT = new JsonGuard<>(AgentStorageException.class, AgentStorageException::new, JsonColumns.mapper());
    // Stored runs keep fields the review record has since dropped, such as summary and coverage limitations.
    static final JsonGuard<ReviewConflictException> REVIEW = new JsonGuard<>(ReviewConflictException.class, ReviewConflictException::new, JsonColumns.mapper()
            .addMixIn(ContextSourceRead.class, SourceReadJson.class)
            .addMixIn(ContextReference.class, ReferenceJson.class)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    static final JsonGuard<CherryPickStorageException> CHERRY_PICK = new JsonGuard<>(CherryPickStorageException.class, CherryPickStorageException::new, JsonColumns.mapper());
    static final JsonGuard<CiAutofixStorageException> CI_AUTOFIX = new JsonGuard<>(
            CiAutofixStorageException.class, CiAutofixStorageException::new, JsonColumns.mapper().disable(WRITE_DATES_AS_TIMESTAMPS));

    private final Class<E> failureType;
    private final BiFunction<String, Throwable, E> newFailure;
    private final ObjectMapper mapper;

    private JsonGuard(Class<E> failureType, BiFunction<String, Throwable, E> newFailure, ObjectMapper mapper)
    {
        this.failureType = requireNonNull(failureType, "failureType is null");
        this.newFailure = requireNonNull(newFailure, "newFailure is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    String write(Object value, String label)
    {
        return guard("Cannot serialize " + label, objectMapper -> objectMapper.writeValueAsString(value));
    }

    <T> T read(String json, Class<T> type, String label)
    {
        return guard("Cannot deserialize " + label, objectMapper -> objectMapper.readValue(json, type));
    }

    <T> T read(String json, TypeReference<T> type, String label)
    {
        return guard("Cannot deserialize " + label, objectMapper -> objectMapper.readValue(json, type));
    }

    <T> T guard(String message, Call<T> call)
    {
        try {
            return call.call(mapper);
        }
        catch (JsonProcessingException | RuntimeException e) {
            if (failureType.isInstance(e)) {
                throw failureType.cast(e);
            }
            throw newFailure.apply(message, e);
        }
    }

    interface Call<T>
    {
        T call(ObjectMapper mapper)
                throws JsonProcessingException;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextRepositoryRead.class, name = "REPOSITORY_FILE"),
            @JsonSubTypes.Type(value = ContextDocumentRead.class, name = "DOCUMENT"),
            @JsonSubTypes.Type(value = ContextHistoryRead.class, name = "HISTORY")})
    private interface SourceReadJson {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextReference.RepositoryFile.class, name = "REPOSITORY_FILE"),
            @JsonSubTypes.Type(value = ContextReference.PullRequest.class, name = "PR"),
            @JsonSubTypes.Type(value = ContextReference.Issue.class, name = "ISSUE"),
            @JsonSubTypes.Type(value = ContextReference.Task.class, name = "TASK"),
            @JsonSubTypes.Type(value = ContextReference.Review.class, name = "REVIEW"),
            @JsonSubTypes.Type(value = ContextReference.Document.class, name = "DOCUMENT")})
    private interface ReferenceJson {}
}
