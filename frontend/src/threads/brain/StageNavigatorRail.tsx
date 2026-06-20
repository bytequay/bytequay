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
import type { StageDto } from '../../types/brainView';
import { railStateFor, stageDisplayName, type RailState } from './stageMeta';

type Props = {
  stages: StageDto[];
  subStages: StageDto[];
  threadTitle: string;
  /** Size badge for the Brain chip — the brain conversation length. */
  brainCount: number;
  onOpenThread: () => void;
  onOpenStage: (stageId: string) => void;
};

const RAIL_GLYPH: Record<RailState, string> = {
  done: '✓',
  active: '◼',
  idle: '⊙',
  future: '◯',
};

function subLine(stage: StageDto, state: RailState): string {
  switch (state) {
    case 'done': return `closed · ${stage.loopIteration} iter`;
    case 'active': return `active · iter #${stage.loopIteration} · running`;
    case 'idle': return `idle · ${stage.loopIteration} iter so far`;
    case 'future': return 'not yet opened';
  }
}

function StageChip({ stage, onOpen }: { stage: StageDto; onOpen: () => void }) {
  const state = railStateFor(stage);
  return (
    <button type="button" className={`stage-chip ${state}`} onClick={onOpen}>
      <span className="gl" aria-hidden>{RAIL_GLYPH[state]}</span>
      <div>
        <div className="nm">{stageDisplayName(stage.type)}</div>
        <div className="sub">{subLine(stage, state)}</div>
      </div>
      {state === 'active'
        ? <span className="ct warn" aria-hidden>⊕</span>
        : <span className="ct">{state === 'future' ? '—' : stage.loopIteration || '·'}</span>}
    </button>
  );
}

function SubStageChip({ stage, index, onOpen }: { stage: StageDto; index: number; onOpen: () => void }) {
  const state = railStateFor(stage);
  return (
    <button type="button" className={`stage-chip ${state}`} onClick={onOpen}>
      <span className="gl" aria-hidden>{RAIL_GLYPH[state]}</span>
      <div>
        <div className="nm">{stageDisplayName(stage.type)} #{index + 1}</div>
        <div className="sub">{stage.summary}</div>
      </div>
      <span className="ct" aria-hidden>·</span>
    </button>
  );
}

/**
 * Left rail — the stage navigator. Lists the current "Brain (you are
 * here)" view, the four lifecycle stages, and the spawned ReviewStage
 * sub-stages. Each stage chip drills into that stage's detail surface.
 */
export function StageNavigatorRail({
  stages, subStages, threadTitle, brainCount, onOpenThread, onOpenStage,
}: Props) {
  return (
    <aside className="rail">
      <button type="button" className="up-thread" onClick={onOpenThread}>
        ↑ Thread · {threadTitle}
      </button>

      <div>
        <div className="sec-h">View</div>
        <div className="stages-list">
          <div className="stage-chip brain" aria-current="page">
            <span className="gl" aria-hidden>⊕</span>
            <div>
              <div className="nm">Brain (you are here)</div>
              <div className="sub">summary · ask · steer</div>
            </div>
            <span className="ct">{brainCount}</span>
          </div>
        </div>
      </div>

      <div>
        <div className="sec-h">Lifecycle <span className="r">{stages.length} stages</span></div>
        <div className="stages-list">
          {stages.map(stage => (
            <StageChip key={stage.id} stage={stage} onOpen={() => onOpenStage(stage.id)} />
          ))}
        </div>
      </div>

      {subStages.length > 0 && (
        <div>
          <div className="sec-h">Sub-stages <span className="r">{subStages.length} panels</span></div>
          <div className="group-label">Spawned from internal review</div>
          <div className="stages-list sub">
            {subStages.map((stage, i) => (
              <SubStageChip key={stage.id} stage={stage} index={i} onOpen={() => onOpenStage(stage.id)} />
            ))}
          </div>
        </div>
      )}
    </aside>
  );
}
