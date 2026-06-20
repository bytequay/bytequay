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
package com.bytequay.app.service.stage;

import com.bytequay.app.beans.stage.StageDetailData;

import java.util.UUID;

/**
 * Read/compose API for the stage drill-in page. Pure reads over data
 * M3/M3.5/M4 already wrote; no operation events, per-iteration CI-fix
 * detail, or uncomputed metrics are fabricated.
 */
public interface StageDetailService
{
    /** The full drill-in payload for one stage instance. */
    StageDetailData getDetail(UUID stageId);
}
