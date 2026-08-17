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

import com.bytequay.agent.core.BlobRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestCiFailureSignature
{
    private static final String HEAD_SHA = "1111111111111111111111111111111111111111";

    @Test
    void computesStableBoundedSignatureFromProgramFacts()
    {
        CiJob job = failedJob();
        CiWorkflowRun workflow = failedWorkflow(job);
        BoundedCiLog firstLog = log(List.of("error: cannot find symbol", "build failed"));
        BoundedCiLog sameLog = log(List.of("error: cannot find symbol", "build failed"));

        String first = CiFailureSignature.compute(workflow, job, firstLog);
        String second = CiFailureSignature.compute(workflow, job, sameLog);
        String changed = CiFailureSignature.compute(
                workflow,
                job,
                log(List.of("error: incompatible types", "build failed")));

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("ci-[0-9a-f]{64}");
        assertThat(changed).isNotEqualTo(first);
    }

    private static CiWorkflowRun failedWorkflow(CiJob job)
    {
        return new CiWorkflowRun(
                900,
                80,
                "Build",
                2,
                "completed",
                Optional.of("failure"),
                HEAD_SHA,
                "https://github.com/actions/runs/900",
                List.of(job));
    }

    private static CiJob failedJob()
    {
        return new CiJob(
                902,
                900,
                "Compile",
                2,
                "completed",
                Optional.of("failure"),
                "https://github.com/actions/jobs/902",
                List.of(new CiStep(3, "Compile Java", "completed", Optional.of("failure"))));
    }

    private static BoundedCiLog log(List<String> errors)
    {
        return new BoundedCiLog(
                Optional.of(new BlobRef("blobs/log", "text/plain", 10)),
                10,
                false,
                errors,
                Optional.empty());
    }
}
