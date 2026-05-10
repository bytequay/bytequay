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
package com.bytequay.app.service.slack;

/**
 * Helpers for Slack {@code ts} strings. Slack ts is
 * {@code "<seconds>.<microseconds>"}; numerically compared the strings
 * are monotonic, but lexicographic compare only works iff both sides
 * have the same integer width. This helper picks the right path.
 */
public final class SlackTs
{
    private SlackTs() {}

    public static int compare(String a, String b)
    {
        if (a == null || a.isEmpty()) {
            return b == null || b.isEmpty() ? 0 : -1;
        }
        if (b == null || b.isEmpty()) {
            return 1;
        }
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        }
        catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
