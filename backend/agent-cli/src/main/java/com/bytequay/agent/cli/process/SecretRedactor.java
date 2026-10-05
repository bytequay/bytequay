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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableList;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Replaces known credential environment values before diagnostics or events leave a CLI driver. */
public final class SecretRedactor
{
    private static final String REPLACEMENT = "[REDACTED]";

    private final List<String> secrets;

    private SecretRedactor(List<String> secrets)
    {
        this.secrets = ImmutableList.copyOf(requireNonNull(secrets, "secrets is null"));
    }

    /**
     * Builds a redactor from environment entries whose names identify credentials.
     *
     * @param environment exact environment passed to the child process
     * @return redactor for non-blank credential values
     */
    public static SecretRedactor fromEnvironment(Map<String, String> environment)
    {
        requireNonNull(environment, "environment is null");
        return new SecretRedactor(environment.entrySet().stream()
                .filter(entry -> isCredentialName(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && value.length() >= 4)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(toImmutableList()));
    }

    /** Returns a redactor that also replaces one runtime-generated credential. */
    public SecretRedactor withSecret(String secret)
    {
        requireNonNull(secret, "secret is null");
        if (secret.length() < 4 || secrets.contains(secret)) {
            return this;
        }
        return new SecretRedactor(ImmutableList.<String>builder()
                .addAll(secrets)
                .add(secret)
                .build()
                .stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(toImmutableList()));
    }

    /**
     * Replaces every known credential value with a stable marker.
     *
     * @param value provider text or diagnostic
     * @return redacted text
     */
    public String redact(String value)
    {
        requireNonNull(value, "value is null");
        String redacted = value;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, REPLACEMENT);
        }
        return redacted;
    }

    /**
     * Redacts the next chunk of a stream as one text with what the stream held back, so a credential split across chunks
     * is still replaced. Text that could begin a credential is held back until the next chunk shows whether it does.
     *
     * @param held what the previous chunk held back, empty for a new stream
     * @param chunk the next chunk
     * @return the redacted text that can be shown now, and the text held back
     */
    public Chunk redactChunk(String held, String chunk)
    {
        requireNonNull(held, "held is null");
        requireNonNull(chunk, "chunk is null");
        String redacted = redact(held + chunk);
        int keep = 0;
        for (String secret : secrets) {
            for (int length = Math.min(secret.length() - 1, redacted.length()); length > keep; length--) {
                if (redacted.regionMatches(redacted.length() - length, secret, 0, length)) {
                    keep = length;
                    break;
                }
            }
        }
        return new Chunk(redacted.substring(0, redacted.length() - keep), redacted.substring(redacted.length() - keep));
    }

    public record Chunk(String text, String held)
    {
        public Chunk
        {
            requireNonNull(text, "text is null");
            requireNonNull(held, "held is null");
        }
    }

    private static boolean isCredentialName(String name)
    {
        String normalized = name.toUpperCase();
        return normalized.contains("TOKEN")
                || normalized.contains("SECRET")
                || normalized.contains("PASSWORD")
                || normalized.contains("API_KEY")
                || normalized.contains("APIKEY")
                || normalized.contains("CREDENTIAL");
    }
}
