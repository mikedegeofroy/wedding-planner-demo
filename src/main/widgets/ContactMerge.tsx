import { useId, type ReactNode } from "react";
import {
  Badge, Button, Card, CardContent, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  registerListSelection, toast, useEffect, useRef, useState, type ListSelectionProps,
} from "@onno/widget-sdk";

type ContactCard = { id: string; code: string; values: Record<string, string>; conversations: number; inquiries: number };
type Option = { value: string; label: string; contacts: string[] };
type Field = { key: string; label: string; conflict: boolean; options: Option[] };
type Preview = { cards: ContactCard[]; fields: Field[]; suggestedTarget: string };

const csrf = () => {
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  return token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {};
};
const post = async (url: string, body: unknown) => {
  const response = await fetch(url, {
    method: "POST", credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...csrf() }, body: JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.detail || data.message || "The merge could not be completed. Please try again.");
  }
  return response.status === 204 ? null : await response.json();
};
const name = (card: ContactCard | undefined) => card?.values.description?.trim() || card?.code || "Untitled contact";
const counts = (card: ContactCard) => `${card.conversations} conversation${card.conversations === 1 ? "" : "s"}`
  + ` · ${card.inquiries} inquir${card.inquiries === 1 ? "y" : "ies"}`;

// The host keeps lucide to itself, so the two glyphs this widget draws are inlined at lucide's own
// geometry — "merge" for the toolbar button and header tile, "x" for the dialog's close control.
const MergeIcon = ({ className }: { className?: string }) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
  strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
  <path d="m8 6 4-4 4 4" /><path d="M12 2v10.3a4 4 0 0 1-1.172 2.872L4 22" /><path d="m20 22-5-5" />
</svg>;
const CloseIcon = () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
  strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>;

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
// A host Select portals its listbox out to document.body and owns Escape and focus while it is open.
// The dialog must stand back for exactly that long, or picking an option closes the whole dialog.
const layerOpen = () => !!document.querySelector("[data-radix-popper-content-wrapper],[data-radix-select-content]");

/**
 * The host's own action dialogs are a react-aria modal the SDK does not re-export, so this is the
 * same shell hand-built from the host's tokens: backdrop, card, header tile, scrolling body, sticky
 * footer — plus the focus containment, Escape and backdrop dismissal a modal owes its user.
 */
function Modal({ title, description, onClose, children, footer }: {
  title: string; description: string; onClose: () => void; children: ReactNode; footer: ReactNode;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const restore = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    dialog.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (layerOpen()) return;
      if (event.key === "Escape") { event.stopPropagation(); onClose(); return; }
      if (event.key !== "Tab") return;
      const stops = [...(dialog.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])];
      if (!stops.length) return;
      const edge = event.shiftKey ? stops[0] : stops[stops.length - 1];
      if (document.activeElement === edge || !dialog.current?.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? stops[stops.length - 1] : stops[0]).focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.body.style.overflow = overflow;
      restore?.focus?.();
    };
  }, [onClose]);

  return <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-4 backdrop-blur-[1px]"
    onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId}
      className="flex max-h-[calc(100dvh-2rem)] w-full flex-col overflow-hidden rounded-card border border-border bg-card text-card-foreground shadow-2xl outline-none sm:max-h-[88dvh] sm:max-w-xl">
      <header className="flex shrink-0 items-start gap-3 border-b border-border px-5 py-4">
        <div className="mt-0.5 grid size-9 shrink-0 place-items-center rounded-field border border-primary/30 bg-primary/10 text-primary">
          <MergeIcon className="size-5" />
        </div>
        <div className="min-w-0 flex-1">
          <h2 id={titleId} className="text-base font-semibold text-foreground">{title}</h2>
          <p id={descriptionId} className="mt-1 text-sm leading-relaxed text-muted-foreground">{description}</p>
        </div>
        <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label="Close"
          className="size-8 shrink-0 text-muted-foreground hover:text-foreground"><CloseIcon /></Button>
      </header>
      {children}
      <footer className="flex shrink-0 justify-end gap-2 border-t border-border bg-card px-5 py-4">{footer}</footer>
    </div>
  </div>;
}

/**
 * One host Select in the dialog. The listbox is portalled to the body, where the dialog's own
 * backdrop (z-70) would otherwise paint over the host's z-50 content — so it is lifted above it.
 */
function Choice({ label, hint, value, onChange, disabled, children }: {
  label: string; hint?: ReactNode; value: string; onChange: (value: string) => void;
  disabled?: boolean; children: ReactNode;
}) {
  const id = useId();
  return <div className="space-y-1.5">
    <Label htmlFor={id}>{label}{hint}</Label>
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id={id} className="w-full"><SelectValue /></SelectTrigger>
      <SelectContent className="z-[80]">{children}</SelectContent>
    </Select>
  </div>;
}

function ContactMerge({ ids, complete }: ListSelectionProps) {
  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [target, setTarget] = useState("");
  // Only the fields the reviewer actually decided; the rest follow whichever card is kept.
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = useId();
  const key = ids.join(",");

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true); setError(""); setPreview(null); setChosen({});
    void post("/api/planner/contacts/merge/preview", { ids })
      .then((data: Preview) => { if (cancelled) return; setPreview(data); setTarget(data.suggestedTarget); })
      .catch(e => { if (!cancelled) setError((e as Error).message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [open, key]);

  const cards = preview?.cards ?? [];
  const kept = cards.find(c => c.id === target);
  // An undecided field keeps the retained card's value, falling back to the first card that has one.
  const valueFor = (field: Field) => chosen[field.key]
    ?? (kept?.values[field.key]?.trim() ? kept.values[field.key] : field.options[0]?.value ?? "");
  const archived = cards.filter(card => card.id !== target);

  const submit = async () => {
    if (busy || !preview || !target) return;
    setBusy(true); setError("");
    try {
      const keep: Record<string, string> = {};
      for (const field of preview.fields) { const value = valueFor(field); if (value) keep[field.key] = value; }
      const result = await post("/api/planner/contacts/merge", { ids, targetId: target, keep });
      setOpen(false);
      toast.success(`${result.merged} contact${result.merged === 1 ? "" : "s"} merged into ${name(kept)}.`);
      complete();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  // One contact is a valid selection for every other toolbar command, so the button stays put and
  // says why it cannot run rather than appearing and disappearing as rows are ticked.
  const tooFew = ids.length < 2;
  return <>
    <Button size="toolbar" variant="subtle" disabled={tooFew} onClick={() => setOpen(true)}
      title={tooFew ? "Select at least two contacts to merge" : undefined}>
      <MergeIcon />Merge {ids.length} contact{ids.length === 1 ? "" : "s"}
    </Button>
    {open && <Modal title="Merge contacts" onClose={() => { if (!busy) setOpen(false); }}
      description="Choose the card to keep, then pick which value to keep wherever the cards disagree. The other cards are archived and their conversations, inquiries and events move across."
      footer={<>
        <Button type="button" variant="ghost" disabled={busy} onClick={() => setOpen(false)}>Cancel</Button>
        <Button type="submit" form={`${id}-form`} disabled={busy || !target}>{busy ? "Merging…" : `Merge into ${name(kept)}`}</Button>
      </>}>
      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
        {loading && <p role="status" className="text-sm text-muted-foreground">Comparing contacts…</p>}
        {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
        {preview && <form id={`${id}-form`} className="space-y-4" onSubmit={e => { e.preventDefault(); void submit(); }}>
          <Choice label="Contact card to keep" value={target} onChange={setTarget} disabled={busy}>
            {cards.map(card => <SelectItem key={card.id} value={card.id}>
              {name(card)} · {card.code} · {counts(card)}
            </SelectItem>)}
          </Choice>
          {archived.length > 0 && <p className="text-xs text-muted-foreground">
            Archived after the merge: {archived.map(card => <Badge key={card.id} variant="secondary" className="mr-1">{name(card)}</Badge>)}
          </p>}
          {preview.fields.filter(f => f.conflict).map(field => <Choice key={field.key} label={field.label}
            hint={<span className="ml-2 text-xs font-normal text-muted-foreground">cards disagree</span>}
            value={valueFor(field)} disabled={busy}
            onChange={value => setChosen(previous => ({ ...previous, [field.key]: value }))}>
            {field.options.map(option => <SelectItem key={option.value} value={option.value}>
              {option.label} — from {option.contacts.map(c => name(cards.find(card => card.id === c))).join(", ")}
            </SelectItem>)}
          </Choice>)}
          {preview.fields.some(f => !f.conflict && f.options.length === 1) && <Card>
            <CardContent className="space-y-1 p-3">
              <Label className="text-xs uppercase tracking-wide text-muted-foreground">Kept as-is</Label>
              {preview.fields.filter(f => !f.conflict && f.options.length === 1).map(field =>
                <p key={field.key} className="text-sm"><span className="text-muted-foreground">{field.label}: </span>{field.options[0].label}</p>)}
            </CardContent>
          </Card>}
        </form>}
      </div>
    </Modal>}
  </>;
}

registerListSelection("plannerContactMerge", ContactMerge);
