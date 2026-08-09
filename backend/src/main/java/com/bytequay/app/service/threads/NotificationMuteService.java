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

import com.google.common.collect.ImmutableSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Workspace mute rules. Actionable rows can never be muted. */
@Service
public class NotificationMuteService
{
    private static final Set<String> PROTECTED_TYPES = ImmutableSet.of(
            "approval-gate", "agent-question", "budget");

    private final JdbcTemplate jdbc;

    public NotificationMuteService(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public List<MuteRule> list(String workspaceId)
    {
        return jdbc.query("""
                SELECT public_type, muted
                FROM workspace_notification_mute
                WHERE workspace_id = ?
                ORDER BY public_type
                """,
                (rs, ignored) -> new MuteRule(
                        rs.getString("public_type"), rs.getBoolean("muted")),
                workspaceId);
    }

    @Transactional
    public MuteRule set(String workspaceId, String publicType, boolean muted)
    {
        if (publicType == null || publicType.isBlank()) {
            throw new IllegalArgumentException(
                    "notification type is required");
        }
        if (isProtected(publicType) && muted) {
            throw new IllegalArgumentException(
                    "actionable notifications cannot be muted");
        }
        jdbc.update("""
                INSERT INTO workspace_notification_mute (
                    workspace_id, public_type, muted, updated_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(workspace_id, public_type) DO UPDATE SET
                    muted = excluded.muted,
                    updated_at_ms = excluded.updated_at_ms
                """,
                workspaceId, publicType, muted, Instant.now().toEpochMilli());
        return new MuteRule(publicType, muted);
    }

    public boolean muted(String workspaceId, String publicType)
    {
        if (isProtected(publicType)) {
            return false;
        }
        Boolean value = jdbc.query("""
                SELECT muted
                FROM workspace_notification_mute
                WHERE workspace_id = ? AND public_type = ?
                """,
                rs -> rs.next() && rs.getBoolean(1),
                workspaceId, publicType);
        return Boolean.TRUE.equals(value);
    }

    public static boolean isProtected(String publicType)
    {
        return PROTECTED_TYPES.contains(publicType);
    }

    public record MuteRule(String publicType, boolean muted) {}
}
