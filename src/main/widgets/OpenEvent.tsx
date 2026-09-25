import {
  Button, React, registerExtension, useEffect, useRef, useState,
  type ExtensionProps,
} from "@onno/widget-sdk";

type ClientEvent = { id: string; name: string; startDate: string | null };

/** Any lucide glyph by name, drawn by the shell's onno-icon bridge (same helper as LogCall.tsx). */
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
 * The chat header's way into the wedding, in the place the CRM's own "Open contact" button used to
 * sit. It only appears for someone who is a client of an event; a supplier's chat, or a couple who
 * has not booked yet, has no wedding to open and shows nothing.
 */
function OpenEvent({ context }: ExtensionProps) {
  const customerId = String(context.record?.customer ?? "");
  const [events, setEvents] = useState<ClientEvent[]>([]);

  useEffect(() => {
    setEvents([]);
    if (!customerId) return;
    let live = true;
    fetch(`/api/planner/contacts/${encodeURIComponent(customerId)}/events`, { credentials: "same-origin" })
      .then(response => response.ok ? response.json() : [])
      .then((rows: ClientEvent[]) => { if (live) setEvents(rows); })
      .catch(() => {});
    return () => { live = false; };
  }, [customerId]);

  const event = events[0];
  if (!event) return null;
  const more = events.length > 1 ? ` (+${events.length - 1})` : "";
  return (
    <Button size="toolbar" variant="subtle" className="gap-1.5" title={event.name}
      onClick={() => window.dispatchEvent(new CustomEvent("onno:action", { detail: `onno://main/catalogs/event_projects/${event.id}` }))}>
      <Icon name="calendar-heart" />Open event{more}
    </Button>
  );
}

registerExtension({
  id: "planner.open-event",
  slot: "crm.chat.header",
  order: 5,
  visible: context => context.surface === "crm-chat",
  component: OpenEvent,
});
