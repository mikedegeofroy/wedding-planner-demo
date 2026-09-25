import type { CSSProperties } from 'react';
import {
  Button, Popover, PopoverContent, PopoverTrigger,
  registerExtension, useCallback, useEffect, useState,
  type ExtensionProps,
} from '@onno/widget-sdk';

// The only glyph here is the forward arrow. A stage reads by its own colour — a flag, a chevron
// and a tick beside it just repeat what the colour already says. App widgets bundle without
// lucide, so the arrow is inline in lucide's geometry (24-grid, 2px round strokes).
const ArrowRight = ({ size = 16, style }: { size?: number; style?: CSSProperties }) =>
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="shrink-0" style={style}
    aria-hidden="true">
    <path d="M5 12h14M12 5l7 7-7 7" />
  </svg>;

// The pipeline itself is the host's: ConversationView declares one action per pipeline stage and
// LeadPipelineService writes the inquiry. This control reads where the client stands
// (/api/planner/pipeline) and runs those actions — one click forward, or a pick from the list.
type Stage = { key: string; label: string; color: string; action: string };
type Pipeline = { current: Stage | null; next: Stage | null; client: string | null; stages: Stage[] };

async function pipelineOf(conversation: string): Promise<Pipeline> {
  const response = await fetch(`/api/planner/pipeline/${conversation}`, { credentials: 'same-origin' });
  if (!response.ok) throw new Error('Could not read the pipeline stage.');
  return response.json();
}

function StageControl({ context }: ExtensionProps) {
  const conversation = context.recordId ?? '';
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(() => {
    if (!conversation) return;
    pipelineOf(conversation).then(setPipeline).catch(e => setError((e as Error).message));
  }, [conversation]);
  useEffect(() => { setPipeline(null); setError(''); load(); }, [load]);

  const move = async (stage: Stage) => {
    if (busy || !context.execute) return;
    setBusy(stage.key); setError('');
    try {
      await context.execute(stage.action, {});
      setOpen(false);
      load();
      await context.refresh?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  };

  if (error) return <span role="alert" className="text-xs text-destructive">{error}</span>;
  if (!pipeline) return null;
  const { current, next, stages } = pipeline;
  const writable = !!context.execute && context.permissions.canReply;
  // The current stage IS the control: its own colour, filled, the way the stage pills read
  // everywhere else in the app.
  const tint: CSSProperties | undefined = current?.color
    ? { color: current.color, borderColor: `${current.color}66`, backgroundColor: `${current.color}1f` }
    : undefined;

  return <div className="flex items-center gap-1" aria-label="Pipeline stage">
    <Popover open={open} onOpenChange={value => { if (!busy) { setOpen(value); if (value) load(); } }}>
      <PopoverTrigger asChild>
        <Button size="toolbar" variant="ghost" className="h-8 rounded-full border px-3"
          style={tint} disabled={!writable}
          aria-label={current ? `Stage: ${current.label}` : 'No pipeline stage yet'}>
          <span className="max-w-40 truncate text-xs font-medium">{current?.label ?? 'No inquiry yet'}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-56 space-y-0.5 rounded-card p-1.5" align="end" sideOffset={8}
        aria-label="Move to a pipeline stage">
        <p className="px-2 py-1 text-xs text-muted-foreground">
          {pipeline.client ? `Move ${pipeline.client} to` : 'Move this client to'}
        </p>
        {stages.map(stage => {
          const active = stage.key === current?.key;
          // The one this client is on is the filled pill; the rest are the same pill unfilled.
          return <Button key={stage.key} size="toolbar" variant="ghost"
            className="h-9 w-full justify-start gap-2 rounded-control font-normal disabled:opacity-100"
            disabled={!!busy || active} onClick={() => void move(stage)}>
            <span aria-hidden="true" className="size-2 shrink-0 rounded-full"
              style={{ backgroundColor: stage.color || 'currentColor' }} />
            <span className="truncate rounded-pill px-2 py-0.5 text-xs font-medium"
              style={stage.color
                ? { color: stage.color, backgroundColor: active ? `${stage.color}26` : 'transparent' }
                : undefined}>{stage.label}</span>
          </Button>;
        })}
        {error && <p role="alert" className="px-2 text-xs text-destructive">{error}</p>}
      </PopoverContent>
    </Popover>
    {/* One click to the next stage — the move people make most, without opening the list. */}
    {next && writable ? <Button size="toolbar" variant="subtle" className="px-2.5"
      disabled={!!busy} onClick={() => void move(next)}
      title={`Move to ${next.label}`} aria-label={`Move to ${next.label}`}>
      <ArrowRight />
    </Button> : null}
  </div>;
}

registerExtension({
  id: 'planner.crm.action.stage',
  slot: 'crm.chat.header',
  order: 10,
  // The pipeline is the Clients inbox. A contractor or venue has no stage to move.
  visible: context => context.workspaceKey === 'clients',
  component: StageControl,
});
