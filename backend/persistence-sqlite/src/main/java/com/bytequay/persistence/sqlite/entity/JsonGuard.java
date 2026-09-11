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
import com.bytequay.core.review.ReviewConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;

/**
 * Runs one Jackson call for a JSON column and reports its failure as the owning module's storage
 * exception, so a corrupt column surfaces as a storage error rather than a parser or validation exception.
 */
final class JsonGuard<E extends RuntimeException>
{
    static final JsonGuard<AgentStorageException> AGENT = new JsonGuard<>(AgentStorageException.class, AgentStorageException::new);
    static final JsonGuard<ReviewConflictException> REVIEW = new JsonGuard<>(ReviewConflictException.class, ReviewConflictException::new);
    static final JsonGuard<CherryPickStorageException> CHERRY_PICK = new JsonGuard<>(CherryPickStorageException.class, CherryPickStorageException::new);
    static final JsonGuard<CiAutofixStorageException> CI_AUTOFIX = new JsonGuard<>(CiAutofixStorageException.class, CiAutofixStorageException::new);

    private final Class<E> failureType;
    private final BiFunction<String, Throwable, E> newFailure;

    private JsonGuard(Class<E> failureType, BiFunction<String, Throwable, E> newFailure)
    {
        this.failureType = requireNonNull(failureType, "failureType is null");
        this.newFailure = requireNonNull(newFailure, "newFailure is null");
    }

    <T> T guard(String message, Call<T> call)
    {
        try {
            return call.call();
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
        T call()
                throws JsonProcessingException;
    }
}
