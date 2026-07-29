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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;

/**
 * Exact call-context fence for the older ReviewPass renderer.
 *
 * <p>ReviewPass execution is already owned by its bounded review executor.
 * Provider calls therefore run synchronously in that owner; this class does
 * not schedule work, acquire workflow capacity, or create another queue.
 */
@Component
public class ReviewCallContext
{
    private final ThreadLocal<Token> current = new ThreadLocal<>();

    public <T> T invoke(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId,
            Callable<T> work)
    {
        Token token = token(pass, lane, attemptId);
        return withCurrent(token, work);
    }

    /** Preserve deterministic submission order without inventing another pool. */
    public <T> List<T> invokeAll(List<Work<T>> work)
    {
        requireNonNull(work, "work is null");
        List<T> results = new ArrayList<>(work.size());
        for (Work<T> item : work) {
            results.add(invoke(
                    item.pass(), item.lane(), item.attemptId(), item.work()));
        }
        // A failed compatibility reviewer deliberately abstains with null.
        // List.copyOf rejects null and would turn one failed seat into an
        // unrelated NullPointerException before the caller can apply its
        // partial/all-failed policy.
        return Collections.unmodifiableList(results);
    }

    /** Fail closed if a raw provider path escaped its exact call context. */
    void requireCurrent(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId)
    {
        Token expected = token(pass, lane, attemptId);
        if (!expected.equals(current.get())) {
            throw new IllegalStateException(
                    "review provider launch has no exact call context");
        }
    }

    Token token(ReviewPass pass, ProviderLane lane, String attemptId)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(lane, "lane is null");
        return new Token(
                requireNonBlank(pass.id(), "pass.id"), lane,
                requireNonBlank(attemptId, "attemptId"));
    }

    private <T> T withCurrent(Token token, Callable<T> work)
    {
        requireNonNull(work, "work is null");
        Token previous = current.get();
        current.set(token);
        try {
            return work.call();
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("review provider call failed", e);
        }
        finally {
            if (previous == null) {
                current.remove();
            }
            else {
                current.set(previous);
            }
        }
    }

    static String attemptId(
            String role,
            String participantId,
            ReviewPhase phase,
            int round,
            String discriminator)
    {
        requireNonBlank(role, "role");
        requireNonBlank(participantId, "participantId");
        requireNonNull(phase, "phase is null");
        requireNonNull(discriminator, "discriminator is null");
        String digest = UUID.nameUUIDFromBytes(
                discriminator.getBytes(StandardCharsets.UTF_8)).toString();
        return role + ":" + participantId + ":" + phase.name() + ":"
                + round + ":" + digest;
    }

    private static String requireNonBlank(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum ProviderLane
    {
        API,
        CLI
    }

    record Token(String passId, ProviderLane lane, String attemptId) {}

    public record Work<T>(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId,
            Callable<T> work)
    {
        public Work
        {
            requireNonNull(pass, "pass is null");
            requireNonNull(lane, "lane is null");
            requireNonBlank(attemptId, "attemptId");
            requireNonNull(work, "work is null");
        }
    }
}
