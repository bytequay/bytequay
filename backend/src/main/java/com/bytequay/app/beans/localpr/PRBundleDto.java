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
package com.bytequay.app.beans.localpr;

import java.util.List;

/**
 * Everything the frontend {@code <PRView>} + push dialog need in one payload,
 * so the bridge hook resolves the whole local PR in a single call instead of
 * five round-trips. {@code pendingStripCount} is the authoritative count of
 * local-only events + local comments a push would strip (design #47) — the
 * push dialog shows it verbatim. {@code syncing} is true while a background
 * refresh of this PR is still running, so the caller knows the snapshot may be
 * a beat behind git/GitHub and can poll for the newer one.
 */
public record PRBundleDto(
        PRDto pr,
        List<PRCommitDto> commits,
        List<PRTimelineEntryDto> timeline,
        List<PRCheckDto> checks,
        List<PRCommentDto> comments,
        int pendingStripCount,
        boolean syncing) {}
