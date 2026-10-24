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
package com.bytequay.integration.google.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGoogleMailHeaders
{
    @Test
    void prefersCaseInsensitiveFoldedReplyToWithMultipleMailboxes()
    {
        String raw = raw("From: Notifications <notifications@example.com>\r\n"
                + "rEpLy-To: \"Support, Team\" <support@example.com>,\r\n\tOther <other@example.com>\r\nSubject: Hello\r\n\r\nBody");
        assertThat(GoogleMailHeaders.replyRecipients(raw)).containsExactly("support@example.com", "other@example.com");
    }

    @Test
    void fallsBackToFromOnlyWhenReplyToIsAbsent()
    {
        assertThat(GoogleMailHeaders.replyRecipients(raw("From: Sender <sender@example.com>\n\nReply-To: body@example.com"))).containsExactly("sender@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "From: from@example.com\r\nReply-To: ",
            "From: from@example.com\r\nReply-To: bad-address",
            "From: from@example.com\r\nReply-To: good@example.com\r\nReply-To: other@example.com",
            "From: from@example.com\r\nReply-To: \"unclosed <good@example.com>",
            "From: from@example.com\r\nReply-To: good@example.com,",
            "From: from@example.com\r\nReply-To: good@example.com\r\n Bcc: other@example.com",
            "From: from@example.com\r\nReply-To: Group: good@example.com;",
    })
    void rejectsInvalidOrAmbiguousReplyToInsteadOfUsingFrom(String headers)
    {
        assertThatThrownBy(() -> GoogleMailHeaders.replyRecipients(raw(headers + "\r\n\r\nBody")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Open Gmail to reply");
    }

    @Test
    void readsOnlyTheHeaderPrefixOfLargeMimeMessages()
    {
        String message = "From: sender@example.com\r\nReply-To: support@example.com\r\n\r\n" + "x".repeat(2 * 1024 * 1024);
        assertThat(GoogleMailHeaders.replyRecipients(raw(message))).containsExactly("support@example.com");
    }

    @Test
    void rejectsInvalidEncodingAndMissingHeaderBoundary()
    {
        assertThatThrownBy(() -> GoogleMailHeaders.replyRecipients("invalid!")).hasMessageContaining("Open Gmail to reply");
        assertThatThrownBy(() -> GoogleMailHeaders.replyRecipients(raw("From: sender@example.com"))).hasMessageContaining("Open Gmail to reply");
    }

    private static String raw(String message)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.getBytes(UTF_8));
    }
}
