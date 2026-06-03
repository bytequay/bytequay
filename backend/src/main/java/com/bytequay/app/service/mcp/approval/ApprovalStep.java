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
package com.bytequay.app.service.mcp.approval;

/**
 * One link in {@link com.bytequay.app.service.mcp.ApprovalPromptHandler}'s
 * chain of responsibility. Steps are walked in Spring {@code @Order}
 * order. Each either resolves the call (parked-state guard, auto-
 * gated target, pre-approved budget, …) or yields to the next step.
 * Falling off the chain hands control to the terminal user-prompt
 * flow back inside the handler.
 *
 * <p>Splitting the original ~140-line method this way means each
 * concern (when to deny vs allow vs escalate) is one small class
 * that can be reasoned about and tested in isolation, and adding a
 * future policy (e.g. "always allow this tool for this role") is one
 * more bean with one more {@code @Order} value — no edit to the
 * handler itself.
 */
public interface ApprovalStep
{
    /** Decide whether this step short-circuits the chain or yields. */
    ApprovalStepResult apply(ApprovalContext ctx);
}
