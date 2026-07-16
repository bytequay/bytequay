import { useCallback, useEffect, useState } from 'react';
import type { AgentReviewQueueItemDto } from '../types';
import AddRepoModal from '../repos/AddRepoModal';

type Scope = 'all' | 'remote' | 'local';

export default function ReviewQueuePage({ onOpenPr, onOpenWorkspace }: {
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  onOpenWorkspace: (workspaceId: string) => void;
}) {
  const [scope, setScope] = useState<Scope>('all');
  const [rows, setRows] = useState<AgentReviewQueueItemDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [clone, setClone] = useState<{ owner: string; repo: string } | null>(null);
  const load = useCallback(async () => {
    try {
      setRows(await window.bridge.listAgentReviewQueue(scope));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [scope]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!rows.some(row => row.status === 'ACTIVE')) return;
    const timer = window.setInterval(() => { void load(); }, 2_000);
    return () => window.clearInterval(timer);
  }, [load, rows]);

  const remote = rows.filter(row => row.remoteOnly);
  const local = rows.filter(row => !row.remoteOnly);
  const open = (row: AgentReviewQueueItemDto) => {
    const [owner, repo] = row.repo.split('/');
    if (owner && repo && row.prNumber !== null) onOpenPr(owner, repo, row.prNumber);
  };

  return (
    <main style={{ maxWidth: 1080, margin: '0 auto', padding: '34px 42px' }}>
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
        <div><h1 style={{ margin: 0 }}>Reviews</h1><p style={{ color: 'var(--ws-text-3)' }}>Progress across remote triage and local repository workspaces.</p></div>
      </header>
      <div style={{ display: 'flex', gap: 8, margin: '22px 0 28px' }}>
        {(['all', 'remote', 'local'] as const).map(value => (
          <button key={value} type="button" className={scope === value ? 'button button--primary button--sm' : 'button button--secondary button--sm'} onClick={() => setScope(value)}>
            {value === 'all' ? `All ${rows.length}` : value === 'remote' ? `Remote ${remote.length}` : `Local ${local.length}`}
          </button>
        ))}
      </div>
      {error && <p role="alert">Couldn’t load reviews: {error}</p>}
      {scope !== 'local' && <QueueSection title="Remote reviews" rows={remote} remote onOpen={open} onClone={setClone} onOpenWorkspace={onOpenWorkspace} />}
      {scope !== 'remote' && <QueueSection title="Local workspace reviews" rows={local} onOpen={open} onClone={setClone} onOpenWorkspace={onOpenWorkspace} />}
      {clone && <AddRepoModal owner={clone.owner} repo={clone.repo} onClose={() => setClone(null)} onMapped={() => {
        void window.bridge.ensureWorkspaceForRepo(clone.owner, clone.repo).then(async workspace => {
          const answer = window.confirm(`Workspace ready: ${clone.owner}/${clone.repo}. Move its remote reviews into this workspace?`);
          if (answer) await window.bridge.adoptRemoteReviews(workspace.id);
          setClone(null);
          await load();
        });
      }} />}
    </main>
  );
}

function QueueSection({ title, rows, remote = false, onOpen, onClone, onOpenWorkspace }: {
  title: string;
  rows: AgentReviewQueueItemDto[];
  remote?: boolean;
  onOpen: (row: AgentReviewQueueItemDto) => void;
  onClone: (repo: { owner: string; repo: string }) => void;
  onOpenWorkspace: (workspaceId: string) => void;
}) {
  if (rows.length === 0) return null;
  return <section style={{ marginBottom: 34 }}>
    <h2 style={{ fontSize: 14, textTransform: 'uppercase', letterSpacing: '.08em', color: 'var(--ws-text-3)' }}>{title}</h2>
    <div style={{ display: 'grid', gap: 10 }}>
      {rows.map(row => {
        const [owner, repo] = row.repo.split('/');
        return <article key={row.reviewId} style={{ border: '1px solid var(--ws-card-border)', borderRadius: 10, padding: '16px 18px', background: 'var(--ws-card)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 14 }}>
            <div><strong>{remote ? 'REMOTE ONLY · GitHub data' : `LOCAL WORKSPACE · ${row.repo}`}</strong><div style={{ marginTop: 7, fontSize: 16 }}>#{row.prNumber ?? '?'} {row.title ?? row.repo}</div><small style={{ color: 'var(--ws-text-3)' }}>{row.roundCount} rounds · {row.findingCount} findings · {row.status}</small></div>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              {remote && owner && repo && <button type="button" className="button button--primary button--sm" onClick={() => onClone({ owner, repo })}>Clone for deep review</button>}
              {!remote && row.workspaceId && <button type="button" className="button button--secondary button--sm" onClick={() => onOpenWorkspace(row.workspaceId)}>Open workspace</button>}
              <button type="button" className="button button--secondary button--sm" onClick={() => onOpen(row)}>Open</button>
            </div>
          </div>
          {remote && <p style={{ margin: '11px 0 0', color: 'var(--ws-text-3)', fontSize: 13 }}>No local source, history, tests, or workspace memory. Findings are marked remote-only.</p>}
        </article>;
      })}
    </div>
  </section>;
}
