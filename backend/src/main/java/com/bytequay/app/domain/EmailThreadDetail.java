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
package com.bytequay.app.domain;

import java.util.List;

/**
 * Full conversation thread including every message, oldest-first.
 * The renderer stacks the messages and lets the user scan the whole
 * thread inline. {@code subject} is taken from the first message
 * (Gmail keeps the original conversation title even if a "Re:" gets
 * added per-reply).
 */
public record EmailThreadDetail(
        String id,
        String subject,
        List<EmailMessageDetail> messages)
{
}
