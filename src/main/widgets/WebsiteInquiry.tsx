import {
  registerChatMessageRenderer, useEffect, useState,
  type ChatMessage, type ChatMessageRendererProps,
} from "@onno/widget-sdk";

/** The colour the Website form channel already carries on every badge and chart in this app. */
const FORM_VIOLET = "#8B5CF6";

const strokes = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

/** A filled-in page. Drawn here: an app's widget bundle carries no icon library. */
function FormIcon({ className = "size-4" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}>
    <rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 8h8M8 12h8M8 16h4" />
  </svg>;
}
function CheckIcon({ className = "size-3" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}><path d="m20 6-11 11-5-5" /></svg>;
}
function CopyIcon({ className = "size-3" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}>
    <rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" />
  </svg>;
}

function field(body: string, key: string) {
  for (const row of body.split("\n")) if (row.startsWith(`${key}: `)) return row.slice(key.length + 2).trim();
  return "";
}

/** A form submission written by the inquiry importer; null for any other internal event. */
function submission(body: string) {
  if (!body?.startsWith("Website inquiry")) return null;
  const [heading] = body.split("\n");
  return {
    at: heading.includes(" · ") ? heading.slice(heading.indexOf(" · ") + 3) : "",
    facts: ([["Wedding date", "Wedding date"], ["Guests", "Guests"], ["Location", "Location"], ["Budget", "Budget"]] as const)
      .map(([label, key]) => ({ label, value: field(body, key) })).filter(row => row.value),
    email: field(body, "Reply to"),
    phone: field(body, "Phone"),
    source: field(body, "Source"),
    page: field(body, "Page"),
  };
}

function Copyable({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => { if (!copied) return; const timer = setTimeout(() => setCopied(false), 1600); return () => clearTimeout(timer); }, [copied]);
  return (
    <button type="button" aria-label={copied ? `${label} copied` : `Copy ${label}`}
      className="inline-flex items-center gap-1 rounded-field px-1 py-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
      onClick={() => { void navigator.clipboard?.writeText(value).then(() => setCopied(true)).catch(() => {}); }}>
      {copied ? <CheckIcon /> : <CopyIcon />}
    </button>
  );
}

/**
 * The form the couple filled in, as its own card above the words they sent with it.
 *
 * <p>It is not a message: nobody can answer a form, and the thread it sits in runs on the address
 * the form captured. What the card is for is the other half of that — showing at a glance what
 * arrived, and which of the details they left is the one being replied to.
 */
function WebsiteInquiryCard({ message }: ChatMessageRendererProps) {
  const form = submission(message.body)!;
  return (
    <article aria-label="Website inquiry"
      className="mx-auto w-full max-w-lg overflow-hidden rounded-panel border border-border bg-card text-foreground shadow-sm">
      <div className="flex items-center gap-3 px-4 py-3" style={{ background: `${FORM_VIOLET}14` }}>
        <span className="flex size-8 shrink-0 items-center justify-center rounded-field"
          style={{ background: `${FORM_VIOLET}24`, color: FORM_VIOLET }}><FormIcon /></span>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold">Website inquiry</h3>
          <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{message.authorName}</p>
        </div>
        {form.at && <time className="shrink-0 text-[10px] text-muted-foreground">{form.at}</time>}
      </div>

      {form.facts.length > 0 && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 px-4 py-3 text-[12px]">
          {form.facts.map(row => (
            <div key={row.label} className="contents">
              <dt className="text-muted-foreground">{row.label}</dt>
              <dd className="font-medium">{row.value}</dd>
            </div>
          ))}
        </dl>
      )}

      {(form.email || form.phone) && (
        <div className="border-t border-border/60 px-4 py-3">
          <p className="pb-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">How to reach them</p>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12px]">
            {form.email && <div className="contents">
              <dt className="text-muted-foreground">Email</dt>
              <dd className="flex min-w-0 items-center gap-1">
                <a className="min-w-0 break-all text-primary underline decoration-primary/40 underline-offset-2" href={`mailto:${form.email}`}>{form.email}</a>
                <Copyable value={form.email} label="email" />
              </dd>
            </div>}
            {form.phone && <div className="contents">
              <dt className="text-muted-foreground">Phone</dt>
              <dd className="flex min-w-0 items-center gap-1">
                <a className="min-w-0 break-all text-primary underline decoration-primary/40 underline-offset-2" href={`tel:${form.phone.replace(/[^+0-9]/g, "")}`}>{form.phone}</a>
                <Copyable value={form.phone} label="phone" />
              </dd>
            </div>}
          </dl>
        </div>
      )}

      {(form.source || form.page) && (
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-border/60 px-4 py-2.5 text-[10px] text-muted-foreground">
          {form.source && <span className="rounded-pill px-2 py-0.5 font-medium"
            style={{ background: `${FORM_VIOLET}1f`, color: FORM_VIOLET }}>{form.source}</span>}
          {form.page && <span className="min-w-0 truncate">{form.page}</span>}
        </div>
      )}
    </article>
  );
}

registerChatMessageRenderer({
  id: "planner.website-inquiry",
  priority: 20,
  matches: (message: Readonly<ChatMessage>) => message.kind === "SYSTEM_EVENT" && !!submission(message.body),
  component: WebsiteInquiryCard,
});
