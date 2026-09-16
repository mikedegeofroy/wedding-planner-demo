import type { ReactNode } from "react";
import {
  registerExtension, useCallback, useEffect, useState, useUiEvents,
  type ExtensionProps,
} from "@onno/widget-sdk";

// App widgets bundle without lucide, so the few glyphs here are inline in lucide's geometry
// (24-grid, round strokes). Everything else the card says, it says in words.
const Glyph = ({ path, size = 14, className = "" }: { path: string; size?: number; className?: string }) =>
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
    strokeLinecap="round" strokeLinejoin="round" className={`shrink-0 ${className}`} aria-hidden="true">
    <path d={path} />
  </svg>;

const INBOUND = "M19 12H5M12 19l-7-7 7-7";
const OUTBOUND = "M5 12h14M12 5l7 7-7 7";
const CLOCK = "M12 8v4l2.5 2.5M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z";
const SPARK = "m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z";

type Stage = { key: string; label: string; color: string };
// The wedding's own facts — date, guests, location, budget — are drawn by the contact panel
// immediately above this card, so the card reads the rest of the endpoint and leaves them to it.
type Wedding = { date: string | null; inDays: number | null; guests: number | null; location: string | null; budget: string | null; budgetBand: string | null };
type ChannelLine = { channel: string; label: string; account: string | null; messages: number; lastAt: string | null; lastDirection: string | null; lastPreview: string | null; unread: number };
type Status = { waitingOnUs: boolean; since: string | null; waitingFor: string; headline: string; lastInbound: string | null; lastOutbound: string | null; touches: number };
type Brief = {
  client: string | null; stage: Stage | null; qualification: string | null; qualificationColor: string | null;
  owner: string | null; summary: string | null; wishes: string | null; wedding: Wedding | null;
  status: Status; channels: ChannelLine[]; next: string[];
};

/** "3d", "4h", "12m" — the resolution a manager chases a reply at, in the width a row can spare. */
function ago(value: string | null): string {
  if (!value) return "";
  const at = new Date(value).getTime();
  if (Number.isNaN(at)) return "";
  const minutes = Math.max(Math.round((Date.now() - at) / 60000), 0);
  if (minutes < 60) return `${Math.max(minutes, 1)}m`;
  const hours = Math.round(minutes / 60);
  return hours < 24 ? `${hours}h` : `${Math.round(hours / 24)}d`;
}

function Pill({ text, color }: { text: string; color: string | null }) {
  return <span className="truncate rounded-pill border px-2 py-0.5 text-[11px] font-medium"
    style={color ? { color, backgroundColor: `${color}1f`, borderColor: `${color}59` } : undefined}>{text}</span>;
}

/** Long free text, clamped to a few lines with the rest a click away. */
function Clamped({ text, lines = 4 }: { text: string; lines?: number }) {
  const [open, setOpen] = useState(false);
  const long = text.length > 180;
  return <div>
    <p className={`whitespace-pre-wrap break-words text-[12px] leading-5 text-foreground ${open || !long ? "" : "overflow-hidden"}`}
      style={open || !long ? undefined : { display: "-webkit-box", WebkitLineClamp: lines, WebkitBoxOrient: "vertical" }}>{text}</p>
    {long && <button type="button" className="mt-1 text-[11px] text-primary underline-offset-2 hover:underline"
      aria-expanded={open} onClick={() => setOpen(value => !value)}>{open ? "Show less" : "Show more"}</button>}
  </div>;
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return <section className="border-t border-border/60 pt-2.5">
    <h4 className="pb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/80">{title}</h4>
    {children}
  </section>;
}

/**
 * What the couple's fields cannot say: who is waiting on whom across every channel they have
 * written on, what they asked for in their own words, where the inquiry stands, and what to do
 * about it. The wedding's facts sit in the contact panel directly above, so the card does not
 * reprint them. Everything shown is a record the app already keeps — the narrative is the
 * inquiry's own and the steps are read off the same fields the pipeline qualifies on — so the card
 * cannot claim more than the data does.
 */
export function LeadBrief({ context }: ExtensionProps) {
  const conversation = context.recordId ?? "";
  const [brief, setBrief] = useState<Brief | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useUiEvents(() => setRevision(value => value + 1), { types: ["created", "updated", "deleted"] });

  const load = useCallback(async (signal: AbortSignal) => {
    const response = await fetch(`/api/planner/brief/${encodeURIComponent(conversation)}`,
      { credentials: "same-origin", signal });
    if (!response.ok) throw new Error("The brief is unavailable for this chat.");
    return response.json() as Promise<Brief>;
  }, [conversation]);

  useEffect(() => {
    if (!conversation) return;
    const controller = new AbortController();
    setBrief(null); setError("");
    void load(controller.signal)
      .then(result => { if (!controller.signal.aborted) setBrief(result); })
      .catch(e => { if (!controller.signal.aborted) setError((e as Error).message); });
    return () => controller.abort();
  }, [conversation, load, revision]);

  if (!conversation) return null;
  return <section aria-label="Client brief" className="rounded-panel border border-border bg-card p-4 text-foreground">
    <header className="flex items-center gap-2 pb-2.5">
      <Glyph path={SPARK} className="text-primary" />
      <h3 className="min-w-0 flex-1 truncate text-sm font-semibold">{brief?.client || "Client brief"}</h3>
      {brief?.stage ? <Pill text={brief.stage.label} color={brief.stage.color} /> : null}
    </header>

    {error ? <p role="alert" className="text-xs text-muted-foreground">{error}</p>
      : !brief ? <p role="status" className="text-xs text-muted-foreground">Loading the brief…</p>
      : <div className="space-y-2.5">
        {/* Who owes whom a message — the one line worth reading before anything else. */}
        <div className={`flex items-center gap-2 rounded-control px-2.5 py-1.5 text-[12px] ${
          brief.status.waitingOnUs ? "bg-amber-500/10 text-amber-700 dark:text-amber-400" : "bg-muted/60 text-muted-foreground"}`}>
          <Glyph path={CLOCK} />
          <span className="min-w-0 flex-1 truncate" title={brief.status.headline}>{brief.status.headline}</span>
        </div>

        {brief.wishes && <Section title="What they asked for"><Clamped text={brief.wishes} /></Section>}

        {brief.summary && <Section title="Where it stands"><Clamped text={brief.summary} lines={5} /></Section>}

        {brief.channels.length > 0 && <Section title={`Across ${brief.channels.length === 1 ? "1 channel" : `${brief.channels.length} channels`}`}>
          <ul className="space-y-1">{brief.channels.map(line => <li key={line.channel}
            className="flex items-center gap-2 text-[12px]" title={line.lastPreview ?? undefined}>
            <Glyph path={line.lastDirection === "INBOUND" ? INBOUND : OUTBOUND} size={12}
              className={line.lastDirection === "INBOUND" ? "text-amber-600 dark:text-amber-400" : "text-muted-foreground"} />
            <span className="min-w-0 flex-1 truncate">{line.label}</span>
            {line.unread > 0 && <span className="rounded-pill bg-primary/15 px-1.5 text-[10px] font-medium text-primary">{line.unread}</span>}
            <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">{line.messages} · {ago(line.lastAt)}</span>
          </li>)}</ul>
        </Section>}

        {brief.next.length > 0 && <Section title="What to do next">
          <ul className="space-y-1.5">{brief.next.map((step, index) => <li key={step} className="flex gap-2 text-[12px] leading-5">
            <span aria-hidden="true" className={`mt-[7px] size-1.5 shrink-0 rounded-full ${index === 0 ? "bg-primary" : "bg-muted-foreground/40"}`} />
            <span className="min-w-0 break-words">{step}</span>
          </li>)}</ul>
        </Section>}
      </div>}
  </section>;
}

registerExtension({
  id: "planner.ai-summary",
  slot: "crm.chat.aside",
  order: 10,
  // The pipeline is the Clients inbox: a contractor or a venue has no stage and no wedding of
  // their own, so there is no brief to draw for them.
  visible: context => context.workspaceKey === "clients",
  component: LeadBrief,
});
