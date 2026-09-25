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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Observed identities for one scope's inputs. A digest establishes inventory equality, not claim correctness.
 *
 * @param scopeId stable scope identity
 * @param inputs observed identities by input key, including explicit absence where a read established it
 * @param digest canonical SHA-256 only when every required input was observed without a gap
 * @param gaps bounded explanations of incomplete input observation
 */
public record ContextInputFingerprint(String scopeId, Map<String, String> inputs, Optional<String> digest, List<String> gaps)
{
    public static final int MAXIMUM_INPUT_BYTES = 32 * 1024;

    public ContextInputFingerprint
    {
        ContextLimits.text(scopeId, "fingerprint scope id", 1100);
        inputs = ImmutableMap.copyOf(inputs);
        checkArgument(inputs.size() <= 128, "Too many fingerprint inputs");
        inputs.forEach((key, value) -> {
            ContextLimits.text(key, "fingerprint input key", 1200);
            ContextLimits.text(value, "fingerprint input value", 2000);
        });
        checkArgument(inputBytes(inputs) <= MAXIMUM_INPUT_BYTES, "Fingerprint inputs exceed UTF-8 budget");
        requireNonNull(digest, "digest is null");
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(gaps.size() <= 10, "Too many fingerprint gaps");
        gaps.forEach(gap -> ContextLimits.text(gap, "fingerprint gap", 2000));
        checkArgument(digest.isPresent() == gaps.isEmpty(), "Only complete fingerprint inputs have a digest");
        checkArgument(digest.isEmpty() || digest.orElseThrow().equals(computeDigest(scopeId, inputs)), "Fingerprint digest does not match its inputs");
    }

    /**
     * Captures the supplied inventory and computes its digest only when observation is complete.
     * The canonical hash frames the scope first, followed by keys and values in natural key order.
     * Every frame is a 32-bit big-endian UTF-8 byte length followed by those bytes. Baseline SHA
     * and notebook revision are not implicit inputs to this hash.
     */
    public static ContextInputFingerprint create(String scopeId, Map<String, String> inputs, List<String> gaps)
    {
        Map<String, String> capturedInputs = ImmutableMap.copyOf(inputs);
        List<String> capturedGaps = ImmutableList.copyOf(gaps);
        Optional<String> digest = capturedGaps.isEmpty() ? Optional.of(computeDigest(scopeId, capturedInputs)) : Optional.empty();
        return new ContextInputFingerprint(scopeId, capturedInputs, digest, capturedGaps);
    }

    /** UTF-8 bytes in the observed keys and values, excluding scope, hash framing, digest and gaps. */
    public int inputBytes()
    {
        return inputBytes(inputs);
    }

    private static int inputBytes(Map<String, String> inputs)
    {
        return inputs.entrySet().stream().mapToInt(entry -> ContextLimits.bytes(entry.getKey()) + ContextLimits.bytes(entry.getValue())).sum();
    }

    private static String computeDigest(String scopeId, Map<String, String> inputs)
    {
        Hasher hasher = Hashing.sha256().newHasher();
        frame(hasher, scopeId);
        new TreeMap<>(inputs).forEach((key, value) -> {
            frame(hasher, key);
            frame(hasher, value);
        });
        return "sha256:" + hasher.hash();
    }

    private static void frame(Hasher hasher, String value)
    {
        byte[] encoded = value.getBytes(UTF_8);
        hasher.putBytes(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array()).putBytes(encoded);
    }
}
