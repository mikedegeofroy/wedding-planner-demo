import {
  registerChatMessageRenderer,
  type ChatMessage, type ChatMessageRendererProps,
} from "@onno/widget-sdk";

/** Jev's own accent, kept apart from every channel and folder colour the inbox already uses. */
const JEV_TEAL = "#0D9488";

const strokes = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

/** A small spark. Drawn here: an app's widget bundle carries no icon library. */
function SparkIcon({ className = "size-4" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}>
    <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6.3 6.3l2.5 2.5M15.2 15.2l2.5 2.5M6.3 17.7l2.5-2.5M15.2 8.8l2.5-2.5" />
  </svg>;
}
function ArrowIcon({ className = "size-3.5" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}><path d="M5 12h14M13 6l6 6-6 6" /></svg>;
}

function field(body: string, key: string) {
  for (const row of body.split("\n")) if (row.startsWith(`${key}: `)) return row.slice(key.length + 2).trim();
  return "";
}

/** "Couple · 100%" → ["Couple", 100]. */
function withPercent(value: string): [string, number | null] {
  const at = value.lastIndexOf(" · ");
  if (at < 0) return [value, null];
  const n = Number.parseInt(value.slice(at + 3), 10);
  return [value.slice(0, at), Number.isFinite(n) ? n : null];
}

/** The card InboundTriageService writes under a first message; null for any other internal event. */
function classification(body: string) {
  if (!body?.startsWith("Jev classification")) return null;
  const [sender, senderPct] = withPercent(field(body, "Sender"));
  const [segment, segmentPct] = withPercent(field(body, "Segment"));
  const routed = field(body, "Routed to");
  return {
    engine: field(body, "Engine"),
    latency: field(body, "Latency"),
    sender, senderPct,
    segment, segmentPct,
    segmentColor: field(body, "Segment color") || JEV_TEAL,
    budget: field(body, "Budget"),
    quoted: field(body, "Quoted"),
    guests: field(body, "Guests"),
    urgency: Number.parseInt(field(body, "Urgency"), 10),
    folder: routed.split(" — ")[0],
    held: routed.includes(" — ") ? routed.slice(routed.indexOf(" — ") + 3) : "",
    folderColor: field(body, "Folder color") || JEV_TEAL,
  };
}

function Meter({ value, color }: { value: number | null; color: string }) {
  if (value == null) return null;
  return (
    <span className="flex items-center gap-1.5">
      <span className="h-1.5 w-14 overflow-hidden rounded-pill bg-muted">
        <span className="block h-full rounded-pill" style={{ width: `${Math.max(4, Math.min(100, value))}%`, background: color }} />
      </span>
      <span className="tabular-nums text-muted-foreground">{value}%</span>
    </span>
  );
}

/**
 * What Jev made of the first message, and where that put the chat. The segment and folder carry
 * the same colours as their badges everywhere else in the app, so the card reads as the reason the
 * chat moved rather than as a separate report.
 */
function JevClassificationCard({ message }: ChatMessageRendererProps) {
  const c = classification(message.body)!;
  const live = c.engine.startsWith("TypeSafe");
  const urgent = Number.isFinite(c.urgency) && c.urgency >= 60;
  return (
    <article aria-label="Jev classification"
      className="mx-auto w-full max-w-lg overflow-hidden rounded-panel border border-border bg-card text-foreground shadow-sm">
      <div className="flex items-center gap-3 px-4 py-3" style={{ background: `${JEV_TEAL}14` }}>
        <span className="flex size-8 shrink-0 items-center justify-center rounded-field"
          style={{ background: `${JEV_TEAL}24`, color: JEV_TEAL }}><SparkIcon /></span>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold">Classified by Jev</h3>
          <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
            {live ? c.engine : "Offline — keyword rules stood in"}{c.latency && ` · ${c.latency}`}
          </p>
        </div>
        {urgent && <span className="shrink-0 rounded-pill px-2 py-0.5 text-[10px] font-semibold"
          style={{ background: "#DC26261f", color: "#DC2626" }}>Urgent</span>}
      </div>

      <dl className="grid grid-cols-[auto_1fr_auto] items-center gap-x-4 gap-y-2 px-4 py-3 text-[12px]">
        <div className="contents">
          <dt className="text-muted-foreground">Sender</dt>
          <dd className="font-medium">{c.sender}</dd>
          <dd><Meter value={c.senderPct} color={JEV_TEAL} /></dd>
        </div>
        <div className="contents">
          <dt className="text-muted-foreground">Segment</dt>
          <dd>
            <span className="rounded-pill px-2 py-0.5 text-[11px] font-semibold"
              style={{ background: `${c.segmentColor}1f`, color: c.segmentColor }}>{c.segment}</span>
          </dd>
          <dd><Meter value={c.segmentPct} color={c.segmentColor} /></dd>
        </div>
        {c.budget && <div className="contents">
          <dt className="text-muted-foreground">Budget</dt>
          <dd className="col-span-2 font-medium">{c.budget}{c.quoted && <span className="ml-1.5 font-normal text-muted-foreground">from “{c.quoted}”</span>}</dd>
        </div>}
        {c.guests && <div className="contents">
          <dt className="text-muted-foreground">Guests</dt>
          <dd className="col-span-2 font-medium">{c.guests}</dd>
        </div>}
        {Number.isFinite(c.urgency) && <div className="contents">
          <dt className="text-muted-foreground">Urgency</dt>
          <dd />
          <dd><Meter value={c.urgency} color={urgent ? "#DC2626" : "#94A3B8"} /></dd>
        </div>}
      </dl>

      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-border/60 px-4 py-2.5 text-[11px]">
        <span className="text-muted-foreground">Filed to</span>
        <ArrowIcon className="size-3.5 text-muted-foreground" />
        <span className="rounded-pill px-2 py-0.5 font-semibold"
          style={{ background: `${c.folderColor}1f`, color: c.folderColor }}>{c.folder}</span>
        {c.held && <span className="min-w-0 text-muted-foreground">{c.held} — a person decides</span>}
      </div>
    </article>
  );
}

registerChatMessageRenderer({
  id: "planner.jev-classification",
  priority: 20,
  matches: (message: Readonly<ChatMessage>) => message.kind === "SYSTEM_EVENT" && !!classification(message.body),
  component: JevClassificationCard,
});
