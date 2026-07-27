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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;

import java.util.Set;

/**
 * Which of a workspace's four engine rows a session runs under. The same
 * four names key the knowledge-retrieval audience and the workspace
 * settings {@code providers} map, so a session that reads "dev" knowledge
 * also runs the workspace's "dev" engine.
 */
public final class SessionAudience
{
    public static final String PLAN = "plan";
    public static final String DEV = "dev";
    public static final String REVIEW = "review";
    public static final String CI_FIX = "ci-fix";

    /** The four names, for validating create-dialog overrides and the
     *  complete per-trunk snapshot written from them. */
    public static final Set<String> ALL = Set.of(PLAN, DEV, REVIEW, CI_FIX);

    private SessionAudience() {}

    /** Trunk-scope turns: planning altitude, unless the whole thread is a
     *  review flow. */
    public static String forThread(Thread thread)
    {
        return thread != null && thread.flow() == ThreadFlow.REVIEW ? REVIEW : PLAN;
    }

    /** Task-scope turns with no stage of their own — ordinary development. */
    public static String forTask(Thread thread)
    {
        return thread != null && thread.flow() == ThreadFlow.REVIEW ? REVIEW : DEV;
    }

    /** Stage-scope turns. A null {@code type} (legacy or non-UUID stage
     *  key) is ordinary development work. */
    public static String forStage(Thread thread, StageType type)
    {
        if (thread != null && thread.flow() == ThreadFlow.REVIEW) {
            return REVIEW;
        }
        if (type == null) {
            return DEV;
        }
        return switch (type) {
            case CI_FIXING_STAGE -> CI_FIX;
            case PLAN_STAGE -> PLAN;
            case REVIEW_STAGE, REVIEW_ROUND_STAGE -> REVIEW;
            default -> DEV;
        };
    }
}
