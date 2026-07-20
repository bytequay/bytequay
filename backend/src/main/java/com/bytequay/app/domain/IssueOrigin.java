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

/** Machine-readable provenance carried inside GitHub issue bodies. */
public final class IssueOrigin
{
    public static final String UNKNOWN = "unknown";
    public static final String USER = "user";
    public static final String USER_REPORT = "user-report";
    public static final String QUALITY_SCAN = "quality-scan";
    public static final String USER_REPORT_MARKER = "<!-- bytequay-origin:v1 kind=user-report -->";
    public static final String QUALITY_SCAN_MARKER = "<!-- bytequay-quality-scan:v1 -->";
    private static final String QUALITY_SCAN_MARKER_PREFIX = "<!-- bytequay-quality-scan:";

    private IssueOrigin() {}

    public static String detect(String body)
    {
        if (body == null) {
            return UNKNOWN;
        }
        if (body.contains(QUALITY_SCAN_MARKER_PREFIX)) {
            return QUALITY_SCAN;
        }
        if (body.contains(USER_REPORT_MARKER)) {
            return USER_REPORT;
        }
        return USER;
    }

    public static String markUserReport(String body)
    {
        String value = body == null ? "" : body.stripTrailing();
        return value.contains(USER_REPORT_MARKER)
                ? value
                : value + "\n\n" + USER_REPORT_MARKER;
    }

    public static String markQualityScan(String body)
    {
        String value = body == null ? "" : body.stripTrailing();
        return value.contains(QUALITY_SCAN_MARKER)
                ? value
                : value + "\n\n" + QUALITY_SCAN_MARKER;
    }
}
