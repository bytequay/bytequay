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
package com.bytequay.app.beans.stage;

/** A pending user-gated decision surfaced in the brain conversation.
 *  {@code tone} is {@code ask} for attention prompts and {@code approve}
 *  for approval gates. */
public record ApprovalDto(
        String tone,
        String stageId,
        String stageTitle,
        String reasonShort,
        String pendingArtifact,
        PrimaryAction primaryAction)
{
    public record PrimaryAction(String label, String href)
    {
    }
}
