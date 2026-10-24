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

import com.google.common.collect.ImmutableList;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

final class GoogleMailHeaders
{
    private static final int MAX_HEADER_BYTES = 65536;
    private static final String UNSUPPORTED = "Could not read the reply address safely. Open Gmail to reply.";

    private GoogleMailHeaders() {}

    static List<String> replyRecipients(String raw)
    {
        String message;
        try {
            // Decode enough complete base64 blocks for the header boundary without copying a MIME attachment.
            int prefixLength = Math.min(raw.length(), 4 * ((MAX_HEADER_BYTES + 6) / 3));
            message = new String(Base64.getUrlDecoder().decode(raw.substring(0, prefixLength)), ISO_8859_1);
        }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(UNSUPPORTED);
        }
        int end = message.indexOf("\r\n\r\n");
        if (end < 0) {
            end = message.indexOf("\n\n");
        }
        checkArgument(end > 0 && end <= MAX_HEADER_BYTES, UNSUPPORTED);
        String unfolded = message.substring(0, end).replaceAll("\r?\n[ \t]+", " ");
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : unfolded.split("\r?\n")) {
            int colon = line.indexOf(':');
            checkArgument(colon > 0, UNSUPPORTED);
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (name.equals("reply-to") || name.equals("from")) {
                checkArgument(!headers.containsKey(name), UNSUPPORTED);
                headers.put(name, line.substring(colon + 1).trim());
            }
        }
        String addresses = headers.getOrDefault("reply-to", headers.getOrDefault("from", ""));
        checkArgument(!addresses.isBlank(), UNSUPPORTED);
        ImmutableList.Builder<String> recipients = ImmutableList.builder();
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int index = 0; index <= addresses.length(); index++) {
            char value = index < addresses.length() ? addresses.charAt(index) : ',';
            if (escaped) {
                escaped = false;
            }
            else if (value == '\\' && quoted) {
                escaped = true;
            }
            else if (value == '"') {
                quoted = !quoted;
            }
            else if (value == ',' && !quoted) {
                String mailbox = addresses.substring(start, index).trim();
                int angle = mailbox.indexOf('<');
                String address = mailbox;
                if (angle >= 0) {
                    checkArgument(mailbox.endsWith(">") && mailbox.indexOf('<', angle + 1) < 0 && mailbox.indexOf('>') == mailbox.length() - 1, UNSUPPORTED);
                    address = mailbox.substring(angle + 1, mailbox.length() - 1).trim();
                }
                checkArgument(address.matches("[\\x21-\\x7e]+") && address.matches("[^\\s@<>(),;:\"]+@[^\\s@<>(),;:\"]+\\.[^\\s@<>(),;:\"]+"), UNSUPPORTED);
                recipients.add(address);
                start = index + 1;
            }
        }
        checkArgument(!quoted && !escaped, UNSUPPORTED);
        return recipients.build();
    }
}
