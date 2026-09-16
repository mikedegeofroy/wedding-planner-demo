import { useId } from "react";
import {
  Button, Input, Label, Popover, PopoverContent, PopoverTrigger, Select, SelectContent, SelectItem,
  SelectTrigger, SelectValue, Switch, Textarea, DatePicker, toast, registerExtension,
  registerChatMessageRenderer, useCallback, useEffect, useState,
  type ChatMessage, type ChatMessageRendererProps, type ExtensionProps,
} from "@onno/widget-sdk";

/**
 * The few glyphs this card needs, drawn here. An app's widget bundle carries no icon library, and
 * five paths are cheaper than making one a dependency of the demo.
 */
const strokes = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
function CheckIcon({ className = "size-4" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}><path d="m20 6-11 11-5-5" /></svg>;
}
function CopyIcon({ className = "size-3" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></svg>;
}
function SpinnerIcon({ className = "size-4" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={`${className} animate-spin`} aria-hidden="true" {...strokes}><circle cx="12" cy="12" r="9" opacity="0.25" /><path d="M21 12a9 9 0 0 0-9-9" /></svg>;
}
function VideoIcon({ className = "size-3.5" }: { className?: string }) {
  return <svg viewBox="0 0 24 24" className={className} aria-hidden="true" {...strokes}><rect x="2" y="6" width="13" height="12" rx="2" /><path d="m16 11 6-4v10l-6-4z" /></svg>;
}

/* ------------------------------------------------------------------ Zoom brand ---- */

const ZOOM_BLUE = "#0B5CFF";

/**
 * The Zoom mark. Drawn rather than linked: the inbox renders offline as happily as online, and a
 * remote image would leave a broken box in the middle of a chat whenever the CDN is unreachable.
 */
function ZoomMark({ size = 20 }: { size?: number }) {
  return (
    <span aria-hidden="true" className="inline-flex shrink-0 items-center justify-center rounded-[22%]"
      style={{ width: size, height: size, background: ZOOM_BLUE }}>
      <svg viewBox="0 0 24 24" width={size * 0.62} height={size * 0.62} fill="none">
        <rect x="2.5" y="6.5" width="12" height="11" rx="2.6" fill="#fff" />
        <path d="M15.8 10.4 20.4 7.4a.75.75 0 0 1 1.15.63v7.94a.75.75 0 0 1-1.15.63l-4.6-3z" fill="#fff" />
      </svg>
    </span>
  );
}

/* ------------------------------------------------------------------ transport ---- */

function csrf(): Record<string, string> {
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  return token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {};
}

async function call<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    credentials: "same-origin",
    ...(body === undefined ? {} : {
      method: "POST",
      headers: { "Content-Type": "application/json", ...csrf() },
      body: JSON.stringify(body),
    }),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || `Zoom is not reachable right now (${response.status})`);
  }
  return response.json();
}

type Account = { connected: boolean; displayName: string; email: string; plan: string; connectedSince: string; timezone: string };
type Meeting = {
  eventId: string; conversationId: string; topic: string; meetingId: string; meetingNumber: number;
  passcode: string; joinUrl: string; startAt: string; minutes: number; timezone: string;
  waitingRoom: boolean; recording: boolean; hostName: string; hostEmail: string; invitation: string;
};

/* ------------------------------------------------------------------ formatting ---- */

const ZOOM_JOIN = /https:\/\/[a-z0-9-]+(?:\.[a-z0-9-]+)*\.?zoom\.us\/j\/\d{9,12}(?:\?[^\s<>"]*)?/i;

function line(body: string, key: string) {
  for (const row of body.split("\n")) if (row.startsWith(`${key}: `)) return row.slice(key.length + 2).trim();
  return "";
}

function when(startAt: string, minutes: number) {
  const start = new Date(startAt);
  if (Number.isNaN(start.getTime())) return startAt;
  const end = new Date(start.getTime() + minutes * 60000);
  const date = start.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
  const clock = (value: Date) => value.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return `${date} · ${clock(start)}–${clock(end)}`;
}

/** "in 2 days" / "in 40 min" / "Live now" / "Ended" — the one thing a reader scans the card for. */
function countdown(startAt: string, minutes: number, now = Date.now()) {
  const start = new Date(startAt).getTime();
  if (!Number.isFinite(start)) return null;
  const end = start + minutes * 60000;
  if (now >= start && now <= end) return { label: "Live now", live: true };
  if (now > end) return { label: "Ended", live: false };
  const away = start - now;
  const days = Math.round(away / 86400000), hours = Math.round(away / 3600000), mins = Math.round(away / 60000);
  if (days >= 2) return { label: `In ${days} days`, live: false };
  if (hours >= 2) return { label: `In ${hours} hours`, live: false };
  return { label: `In ${Math.max(1, mins)} min`, live: false };
}

function Copyable({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => { if (!copied) return; const timer = setTimeout(() => setCopied(false), 1600); return () => clearTimeout(timer); }, [copied]);
  return (
    <button type="button" aria-label={copied ? `${label} copied` : `Copy ${label}`}
      className="inline-flex items-center gap-1 rounded-field px-1 py-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
      onClick={() => { void navigator.clipboard?.writeText(value).then(() => setCopied(true)).catch(() => {}); }}>
      {copied ? <CheckIcon className="size-3" /> : <CopyIcon className="size-3" />}
    </button>
  );
}

function JoinButton({ href, className = "" }: { href: string; className?: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer"
      className={`inline-flex items-center gap-1.5 rounded-field px-3 py-1.5 text-xs font-semibold text-white hover:opacity-90 ${className}`}
      style={{ background: ZOOM_BLUE }}>
      <VideoIcon />Join meeting
    </a>
  );
}

/* ------------------------------------------------------------ the scheduled card ---- */

/** A meeting as written into the conversation by the scheduler; null for any other event. */
function scheduledMeeting(body: string) {
  if (!body?.includes("\nZoom meeting: ")) return null;
  const joinUrl = line(body, "Join");
  if (!ZOOM_JOIN.test(joinUrl)) return null;
  const options = line(body, "Options");
  const host = line(body, "Host");
  const split = host.lastIndexOf(" · ");
  return {
    topic: line(body, "Zoom meeting"), joinUrl, meetingId: line(body, "Meeting ID"),
    passcode: line(body, "Passcode"), startAt: line(body, "Starts"),
    minutes: Number(line(body, "Duration").replace(/[^0-9]/g, "")) || 30,
    timezone: line(body, "Timezone"), options,
    hostName: split < 0 ? host : host.slice(0, split), hostEmail: split < 0 ? "" : host.slice(split + 3),
  };
}

function ScheduledCard({ message }: ChatMessageRendererProps) {
  const meeting = scheduledMeeting(message.body)!;
  const status = countdown(meeting.startAt, meeting.minutes);
  return (
    <article aria-label={`Zoom meeting · ${meeting.topic}`}
      className="mx-auto w-full max-w-lg overflow-hidden rounded-panel border border-border bg-card text-foreground shadow-sm">
      <div className="flex items-center gap-3 px-4 py-3" style={{ background: `${ZOOM_BLUE}14` }}>
        <ZoomMark size={30} />
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-sm font-semibold">{meeting.topic}</h3>
          <p className="mt-0.5 truncate text-[11px] text-muted-foreground">Zoom meeting · {meeting.hostEmail || meeting.hostName}</p>
        </div>
        {status && (
          <span className="shrink-0 rounded-pill px-2 py-1 text-[10px] font-semibold"
            style={status.live
              ? { background: ZOOM_BLUE, color: "#fff" }
              : { background: `${ZOOM_BLUE}1f`, color: ZOOM_BLUE }}>
            {status.label}
          </span>
        )}
      </div>
      <div className="space-y-2.5 px-4 py-3">
        <p className="text-[13px] font-medium">{when(meeting.startAt, meeting.minutes)}</p>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[12px]">
          <dt className="text-muted-foreground">Meeting ID</dt>
          <dd className="flex items-center gap-1 tabular-nums">{meeting.meetingId}<Copyable value={meeting.meetingId.replace(/ /g, "")} label="meeting ID" /></dd>
          <dt className="text-muted-foreground">Passcode</dt>
          <dd className="flex items-center gap-1">{meeting.passcode}<Copyable value={meeting.passcode} label="passcode" /></dd>
        </dl>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border/60 px-4 py-2.5">
        <span className="text-[10px] text-muted-foreground">{meeting.options}{meeting.timezone ? ` · ${meeting.timezone}` : ""}</span>
        <JoinButton href={meeting.joinUrl} />
      </div>
    </article>
  );
}

/* --------------------------------------------------------------- the sent link ---- */

/**
 * The invitation as it appears in a sent message. There is no card here on purpose: what the client
 * receives on WhatsApp or by mail is two lines and a link, and dressing those two lines up in the
 * chat would show the planner something the person on the other end never sees. Only the link is
 * made live, which the plain-text fallback cannot do.
 */
function ZoomLinkBody({ message }: ChatMessageRendererProps) {
  const url = message.body.match(ZOOM_JOIN)![0];
  const at = message.body.indexOf(url);
  return (
    <p className="mt-1 whitespace-pre-wrap break-words text-[13px] leading-5">
      {message.body.slice(0, at)}
      <a href={url} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 hover:opacity-80">{url}</a>
      {message.body.slice(at + url.length)}
    </p>
  );
}

registerChatMessageRenderer({
  id: "planner.zoom.scheduled",
  priority: 20,
  matches: (message: Readonly<ChatMessage>) => message.kind === "SYSTEM_EVENT" && !!scheduledMeeting(message.body),
  component: ScheduledCard,
});

registerChatMessageRenderer({
  id: "planner.zoom.link",
  priority: 10,
  matches: (message: Readonly<ChatMessage>) => message.kind !== "SYSTEM_EVENT" && ZOOM_JOIN.test(message.body ?? ""),
  component: ZoomLinkBody,
});

/* ------------------------------------------------------------------ scheduler ---- */

const DURATIONS = ["15", "30", "45", "60", "90", "120"];

function ScheduleZoom({ context }: ExtensionProps) {
  const id = useId();
  const conversationId = context.recordId ?? "";
  const customerName = String(context.record?.customerDisplay ?? "").trim();
  const [open, setOpen] = useState(false);
  const [account, setAccount] = useState<Account | null>(null);
  const [accountError, setAccountError] = useState("");
  const [requestId, setRequestId] = useState("");
  const [topic, setTopic] = useState("");
  const [startAt, setStartAt] = useState("");
  const [minutes, setMinutes] = useState("45");
  const [waitingRoom, setWaitingRoom] = useState(true);
  const [recording, setRecording] = useState(true);
  const [note, setNote] = useState("");
  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");

  const reset = useCallback(() => {
    setRequestId(crypto.randomUUID());
    setTopic(customerName ? `${customerName} · Wedding Planner` : "Wedding Planner meeting");
    setStartAt(""); setMinutes("45"); setWaitingRoom(true); setRecording(true);
    setMeeting(null); setError(""); setNote(customerName ? `Hi ${customerName.split(" ")[0]}, here is the link for our call.` : "");
  }, [customerName]);

  useEffect(() => {
    if (!open) return;
    void call<Account>("/api/planner/zoom/account")
      .then(value => { setAccount(value); setAccountError(""); })
      .catch(e => setAccountError((e as Error).message));
  }, [open]);

  const create = async () => {
    if (busy) return;
    setBusy(true); setError("");
    try {
      setMeeting(await call<Meeting>("/api/planner/zoom/meetings", {
        requestId, conversationId, topic, startAt, minutes: Number(minutes),
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, waitingRoom, recording,
      }));
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  const send = async () => {
    if (!meeting || sending) return;
    setSending(true); setError("");
    const body = note.trim() ? `${note.trim()}\n\n${meeting.invitation}` : meeting.invitation;
    try {
      // The CRM scopes a send to the workspace the chat is being read in, exactly as the
      // composer beside this button does; without it the conversation is outside every workspace.
      const scope = context.workspaceKey ? `?workspace=${encodeURIComponent(context.workspaceKey)}` : "";
      await call(`/api/crm/conversations/${encodeURIComponent(conversationId)}/messages${scope}`, { body });
      toast.success("Invitation sent");
      setOpen(false);
      await context.refresh?.();
    } catch (e) { setError((e as Error).message); } finally { setSending(false); }
  };

  if (!conversationId || !context.permissions.canWrite) return null;
  return (
    <Popover open={open} onOpenChange={(value: boolean) => { if (busy || sending) return; setOpen(value); if (value) reset(); }}>
      <PopoverTrigger asChild>
        <Button size="toolbar" variant="subtle" className="gap-1.5"><ZoomMark size={16} />Schedule Zoom</Button>
      </PopoverTrigger>
      <PopoverContent align="end" aria-label="Schedule a Zoom meeting"
        className="w-[420px] max-w-[calc(100vw-32px)] max-h-[80vh] overflow-y-auto p-0">
        <header className="flex items-center gap-2.5 border-b border-border px-4 py-3">
          <ZoomMark size={26} />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-semibold leading-4">Zoom</div>
            <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
              {account ? `${account.email} · ${account.plan}` : accountError ? "Account unavailable" : "Checking account…"}
            </div>
          </div>
          <span className="inline-flex shrink-0 items-center gap-1.5 rounded-pill bg-emerald-500/10 px-2 py-1 text-[10px] font-medium text-emerald-600 dark:text-emerald-400">
            <span className="size-1.5 rounded-full bg-emerald-500" />{account ? "Connected" : "…"}
          </span>
        </header>

        {meeting ? (
          <div className="space-y-3 p-4">
            <div className="flex items-center gap-2 text-[13px] font-medium text-emerald-600 dark:text-emerald-400">
              <CheckIcon className="size-4" />Meeting created on Zoom
            </div>
            <div className="rounded-field border border-border p-3">
              <p className="text-[13px] font-medium">{meeting.topic}</p>
              <p className="mt-0.5 text-[11px] text-muted-foreground">{when(meeting.startAt, meeting.minutes)} · {meeting.timezone}</p>
              <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[12px]">
                <dt className="text-muted-foreground">Meeting ID</dt>
                <dd className="flex items-center gap-1 tabular-nums">{meeting.meetingId}<Copyable value={String(meeting.meetingNumber)} label="meeting ID" /></dd>
                <dt className="text-muted-foreground">Passcode</dt>
                <dd className="flex items-center gap-1">{meeting.passcode}<Copyable value={meeting.passcode} label="passcode" /></dd>
              </dl>
            </div>
            <div className="space-y-1">
              <Label htmlFor={`${id}-note`}>Message above the invitation</Label>
              <Textarea id={`${id}-note`} rows={2} maxLength={500} value={note} disabled={sending}
                onChange={(e: { target: { value: string } }) => setNote(e.target.value)} />
            </div>
            {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Button variant="ghost" disabled={sending}
                onClick={() => { void navigator.clipboard?.writeText(meeting.invitation).then(() => toast.success("Invitation copied")).catch(() => {}); }}>
                Copy invitation
              </Button>
              <Button disabled={sending} onClick={() => void send()} style={{ background: ZOOM_BLUE, color: "#fff" }}>
                {sending ? <><SpinnerIcon />Sending…</> : "Send invitation"}
              </Button>
            </div>
          </div>
        ) : (
          <form className="space-y-3 p-4" onSubmit={e => { e.preventDefault(); void create(); }}>
            <div className="space-y-1">
              <Label htmlFor={`${id}-topic`}>Topic</Label>
              <Input id={`${id}-topic`} maxLength={200} value={topic} disabled={busy} onChange={(e: { target: { value: string } }) => setTopic(e.target.value)} />
            </div>
            <div className="flex flex-wrap gap-3">
              <div className="min-w-[210px] flex-1 space-y-1">
                <Label>When</Label>
                <DatePicker value={startAt} onChange={setStartAt} includeTime aria-label="Meeting date and time" isDisabled={busy} />
              </div>
              <div className="min-w-[110px] space-y-1">
                <Label htmlFor={`${id}-minutes`}>Duration</Label>
                <Select value={minutes} onValueChange={setMinutes} disabled={busy}>
                  <SelectTrigger id={`${id}-minutes`}><SelectValue /></SelectTrigger>
                  <SelectContent>{DURATIONS.map(value => <SelectItem key={value} value={value}>{value} minutes</SelectItem>)}</SelectContent>
                </Select>
              </div>
            </div>
            <p className="text-[11px] text-muted-foreground">Time zone: {Intl.DateTimeFormat().resolvedOptions().timeZone}</p>
            <div className="space-y-2 rounded-field border border-border p-3">
              <label className="flex items-center justify-between gap-3 text-[13px]">
                <span>Waiting room</span>
                <Switch checked={waitingRoom} onCheckedChange={setWaitingRoom} disabled={busy} aria-label="Waiting room" />
              </label>
              <label className="flex items-center justify-between gap-3 text-[13px]">
                <span>Record to the cloud</span>
                <Switch checked={recording} onCheckedChange={setRecording} disabled={busy} aria-label="Record to the cloud" />
              </label>
            </div>
            {(error || accountError) && <p role="alert" className="text-xs text-destructive">{error || accountError}</p>}
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" disabled={busy} onClick={() => setOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={busy || !startAt || !topic.trim()} style={{ background: ZOOM_BLUE, color: "#fff" }}>
                {busy ? <><SpinnerIcon />Creating meeting…</> : "Schedule meeting"}
              </Button>
            </div>
          </form>
        )}
      </PopoverContent>
    </Popover>
  );
}

registerExtension({
  id: "planner.zoom.schedule",
  slot: "crm.chat.header",
  order: 10,
  visible: context => context.surface === "crm-chat",
  component: ScheduleZoom,
});
