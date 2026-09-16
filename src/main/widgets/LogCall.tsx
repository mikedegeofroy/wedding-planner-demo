import { useId } from "react";
import {
  Button, Input, Label, Popover, PopoverContent, PopoverTrigger, React, Select, SelectContent,
  SelectItem, SelectTrigger, SelectValue, Textarea, registerExtension, useEffect, useRef, useState,
  type ExtensionProps,
} from "@onno/widget-sdk";

/**
 * Any lucide glyph by name, drawn by the shell's onno-icon bridge — an app's widget bundle carries
 * no icon library. The element takes name/size as DOM properties, so they are assigned through a ref.
 */
function Icon({ name, size = 16 }: { name: string; size?: number }) {
  const ref = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const element = ref.current as (HTMLElement & { name?: string; size?: number }) | null;
    if (!element) return;
    element.name = name;
    element.size = size;
  }, [name, size]);
  return React.createElement("onno-icon", {
    ref, "aria-hidden": "true",
    style: { display: "inline-flex", width: size, height: size, flexShrink: 0 },
  });
}

/**
 * Logging a call that happened elsewhere — a phone call, a site visit, anything the inbox did not
 * carry. The CRM's own button is switched off for this app (it sat beside the Zoom scheduler and
 * offered a second, plainer way to record a meeting), so the part of it this business actually
 * uses lives here instead, writing the same internal event through the same endpoint.
 */
function LogCall({ context }: ExtensionProps) {
  const id = useId();
  const conversationId = context.recordId ?? "";
  const customerId = String(context.record?.customer ?? "");
  const [open, setOpen] = useState(false);
  const [type, setType] = useState("CALL_COMPLETED");
  const [details, setDetails] = useState("");
  const [recording, setRecording] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const save = async () => {
    if (busy || !details.trim()) return;
    setError("");
    if (recording.trim()) {
      try { if (new URL(recording.trim()).protocol !== "https:") throw new Error(); }
      catch { setError("A recording link has to be an https:// address."); return; }
    }
    const body = details.trim() + (recording.trim() ? `\nRecording: ${recording.trim()}` : "");
    if (body.length > 7000) { setError("That summary is too long."); return; }
    setBusy(true);
    try {
      const token: string | undefined = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
      const response = await fetch(`/api/crm/contacts/${encodeURIComponent(customerId)}/activity`, {
        method: "POST", credentials: "same-origin",
        headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) },
        body: JSON.stringify({ conversationId, type, details: body }),
      });
      if (!response.ok) {
        const failure = await response.json().catch(() => ({}));
        throw new Error(failure.detail || failure.message || "The activity could not be saved.");
      }
      setDetails(""); setRecording(""); setOpen(false);
      await context.refresh?.();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  if (!conversationId || !customerId || !context.permissions.canWrite) return null;
  return (
    <Popover open={open} onOpenChange={(value: boolean) => { if (!busy) { setOpen(value); setError(""); } }}>
      <PopoverTrigger asChild>
        <Button size="toolbar" variant="subtle"><Icon name="phone" />Log activity</Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96 max-w-[calc(100vw-32px)] space-y-3 p-4" aria-label="Log activity">
        <div className="text-sm font-semibold">Add a summary</div>
        <Select value={type} onValueChange={setType} disabled={busy}>
          <SelectTrigger aria-label="Activity type"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="CALL_COMPLETED">Call completed</SelectItem>
            <SelectItem value="OTHER">Activity</SelectItem>
          </SelectContent>
        </Select>
        <div className="space-y-1">
          <Label htmlFor={`${id}-summary`}>Summary</Label>
          <Textarea id={`${id}-summary`} rows={3} maxLength={7000} value={details} disabled={busy}
            onChange={(e: { target: { value: string } }) => setDetails(e.target.value)} placeholder="What was discussed, and what happens next?" />
        </div>
        <div className="space-y-1">
          <Label htmlFor={`${id}-recording`}>Recording link</Label>
          <Input id={`${id}-recording`} maxLength={2000} value={recording} disabled={busy}
            onChange={(e: { target: { value: string } }) => setRecording(e.target.value)} placeholder="https://…" />
        </div>
        <p className="text-xs text-muted-foreground">Visible to the team only; the client never sees it.</p>
        {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" disabled={busy} onClick={() => setOpen(false)}>Cancel</Button>
          <Button disabled={busy || !details.trim()} onClick={() => void save()}>{busy ? "Saving…" : "Save"}</Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

registerExtension({
  id: "planner.log-call",
  slot: "crm.chat.header",
  order: 20,
  visible: context => context.surface === "crm-chat",
  component: LogCall,
});
