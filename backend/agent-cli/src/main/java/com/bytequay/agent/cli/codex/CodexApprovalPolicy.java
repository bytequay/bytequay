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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentCommandPolicy;
import com.google.common.io.Resources;

import java.io.IOException;
import java.io.UncheckedIOException;

import static java.nio.charset.StandardCharsets.UTF_8;

final class CodexApprovalPolicy
{
    private static final String POLICY = readPolicy();

    private CodexApprovalPolicy() {}

    static String policy(boolean review)
    {
        return POLICY + "\n" + (review ? AgentCommandPolicy.reviewInstructions() : AgentCommandPolicy.instructions());
    }

    private static String readPolicy()
    {
        try {
            return Resources.toString(Resources.getResource(CodexApprovalPolicy.class, "codex-auto-review-policy.md"), UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Cannot load the Codex automatic approval policy", failure);
        }
    }
}
