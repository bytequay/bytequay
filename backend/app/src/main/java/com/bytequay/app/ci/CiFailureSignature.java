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
package com.bytequay.app.ci;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class CiFailureSignature
{
    private CiFailureSignature() {}

    public static String compute(CiWorkflowRun workflowRun, CiJob job, BoundedCiLog log)
    {
        requireNonNull(workflowRun, "workflowRun is null");
        requireNonNull(job, "job is null");
        requireNonNull(log, "log is null");
        checkArgument(job.workflowRunId() == workflowRun.id(), "job does not belong to workflowRun");

        StringBuilder canonical = new StringBuilder()
                .append(workflowRun.workflowId()).append('\n')
                .append(workflowRun.name().toLowerCase(Locale.ROOT)).append('\n')
                .append(workflowRun.attempt()).append('\n')
                .append(workflowRun.conclusion().orElse("")).append('\n')
                .append(job.name().toLowerCase(Locale.ROOT)).append('\n')
                .append(job.attempt()).append('\n')
                .append(job.conclusion().orElse("")).append('\n');
        for (CiStep step : job.failedSteps()) {
            canonical.append(step.number()).append(':')
                    .append(step.name().toLowerCase(Locale.ROOT)).append(':')
                    .append(step.conclusion().orElse("")).append('\n');
        }
        log.terminalErrorLines().forEach(line -> canonical.append(line.toLowerCase(Locale.ROOT)).append('\n'));
        return "ci-" + hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value)
    {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] value)
    {
        return HexFormat.of().formatHex(value);
    }
}
