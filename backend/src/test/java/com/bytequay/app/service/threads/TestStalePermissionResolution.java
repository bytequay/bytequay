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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStalePermissionResolution
{
    @Test
    void returnsDanglingRequestsOnceSkippingResolvedOnes()
    {
        List<ThreadMessage> messages = List.of(
                req(1, "call-A"),
                decision(2, "call-A"),        // A was answered → not stale
                req(3, "call-B"),             // B dangling → stale
                req(4, "call-C"),
                autoAllowed(5, "call-C"),     // C auto-allowed → not stale
                req(6, "call-D"),
                req(7, "call-D"));            // D dangling, duplicate row → once

        assertThat(ClaudeCodeCliThreadAgent.unresolvedPermissionCallIds(messages))
                .containsExactly("call-B", "call-D");
    }

    @Test
    void emptyWhenEveryRequestWasResolved()
    {
        assertThat(ClaudeCodeCliThreadAgent.unresolvedPermissionCallIds(List.of(
                req(1, "x"), decision(2, "x"))))
                .isEmpty();
    }

    @Test
    void ignoresNonPermissionRows()
    {
        assertThat(ClaudeCodeCliThreadAgent.unresolvedPermissionCallIds(List.of(
                msg(1, "user", "text", "{\"text\":\"hi\"}"),
                msg(2, "assistant", "text", "{\"text\":\"yo\"}"))))
                .isEmpty();
    }

    private static ThreadMessage req(long seq, String callId)
    {
        return msg(seq, "system", "permission_request",
                "{\"callId\":\"" + callId + "\",\"toolName\":\"Bash\",\"summary\":\"x\"}");
    }

    private static ThreadMessage decision(long seq, String callId)
    {
        return msg(seq, "system", "permission_decision",
                "{\"callId\":\"" + callId + "\",\"decision\":\"ALLOW\"}");
    }

    private static ThreadMessage autoAllowed(long seq, String callId)
    {
        return msg(seq, "system", "permission_auto_allowed",
                "{\"callId\":\"" + callId + "\",\"toolName\":\"Bash\",\"remaining\":4}");
    }

    private static ThreadMessage msg(long seq, String role, String type, String content)
    {
        return new ThreadMessage(
                "m-" + seq, "t", null, seq, role, type, content,
                null, null, null, null, Instant.ofEpochMilli(seq),
                null, ThreadScope.TRUNK);
    }
}
