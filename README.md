# Wedding Planner — ONNO presale demo

A deliberately small ONNO 3.0.0 application showing the Wedding Planner presale story:

1. an inquiry arrives with channel and full UTM attribution, and its contact history is kept on it;
2. budget, date, and location drive immediate qualification;
3. €500k+ leads become VIP and route to a founder;
4. `/` is an operations overview — demand, pipeline, production and cash in one window, with
   **Where the leads go**: a funnel from inquiry to signature carrying the drop-off, the loss reason
   and the money at every step, readable for one channel at a time and reporting what each channel
   returns on its spend;
5. **Marketing → Performance** (`/marketing`) reports spend, cost per lead, and the fee revenue each
   channel returns;
6. **Marketing → Lead intelligence** (`/lead-intelligence`) reports attribution (first touch against
   last touch), lead quality by source, speed to lead, and how much contact each couple has had —
   headed by the same funnel, which can be broken down by any of those dimensions.

The app uses the published ONNO framework, UI, auth, and CRM starters. It does not modify the framework.

## Run

Java 21 is required. From the cloned `onno-framework` directory next to this app:

```bash
./gradlew -p ../wedding-planner-demo bootRun
```

Open <http://localhost:8080/ui/>. The inbox is at <http://localhost:8080/ui/inbox>. The demo user is selected automatically.

## Presale scope

### Real in this demo

- ONNO document-backed lead records and generated REST/UI
- deterministic qualification and VIP routing
- UTM attribution (`utm_source`/`medium`/`campaign`/`content`/`term`, landing page, referrer) with
  first-touch and last-touch credit held separately on the lead
- a per-lead touch history (ad click, site visit, message, call, meeting, venue visit, proposal)
  rolled up onto the lead so charts can group by it
- speed to lead, days to booking, and lost reason
- pipeline stages, meeting timestamp, original inquiry, and AI summary fields
- dashboard KPIs and charts on three pages
- a custom funnel widget (`plannerLeadFunnel`, served by `/api/planner/funnel`) drawing the inquiry →
  answered → qualified → met → proposal → booked ladder, each gap's loss and open work, the lost
  reasons behind it, and the wedding value and planning fee on every step, splittable by first
  touch, last touch, channel, budget band or owner. Picking a row of the breakdown redraws the whole
  ladder for that slice alone — one channel's own funnel, with its own drop-off and loss reasons —
  and the breakdown reports each slice's **return**: planning fee booked per € of the channel spend
  its leads carry, blank where nothing was spent
- a channel performance table whose spend is imported and whose results are recomputed from the
  leads by `MarketingRollupService`, so the two can never disagree
- a per-event **internal margin**: Wedding Planner's agency fee, the markup between what an article is quoted
  at and what it costs, and the commission suppliers rebate — forecast from the chosen estimate and
  booked from posted invoices, behind its own role list so it never reaches a client-facing role
- seeded Wedding Planner-shaped demo data: a year of inquiries whose channels behave differently on purpose

### Simulated for presale

- channel delivery from Instagram, WhatsApp, email, and the Tilda form
- AI extraction and FAQ replies (the UI stores the original message and the resulting summary)
- Meta/Google spend import
- calendar booking

These should remain adapters around the ONNO system of record. Building them before the client validates
the workflow adds credentials, provider approvals, message-window rules, and failure handling without
making the core presale story more convincing.

## Smallest next integrations

1. Tilda form webhook → `LeadInquiry` create endpoint.
2. Pluggable qualification service: LLM returns structured budget/date/location/guest-count data; the
   existing domain rules make the final routing decision.
3. Respond.io webhook connector for inbound/outbound conversation events.
4. Google Calendar availability and event creation.
5. Daily Meta/Google spend import to replace the seeded marketing summary rows.

## Reading the marketing numbers

- **Attribution.** `source` is first touch (what created the demand); `lastTouchSource` is the click
  that produced the inquiry, derived from the touch history. The channel table credits last touch,
  because that is the model Meta and Google bill against. Lead intelligence charts both, and the gap
  between them is the finding — on the seeded data, retargeting is credited with leads that press and
  organic created.
- **Revenue is Wedding Planner's planning fee**, not the couple's wedding budget. See `PLANNING_FEE_RATE` in
  `LeadInquiry`. Without it, a €250 lead sits next to a €450k number and every cost figure rounds
  away to nothing.
- **Cost per lead is exact, not an average of averages.** The rollup spreads each channel-month's
  spend across the leads it produced and stores it on the lead, so an average over any slice weights
  by the leads that slice actually contains.
- **Rates are charted, never tiled.** A KPI tile totals the series behind it, which is right for a
  count of leads and silently wrong for a percentage.
- **The funnel counts a lead at the furthest step it reached**, not the stage it sits in today.
  Lost is a terminal stage, so a couple who met, received a proposal and then went quiet would
  otherwise disappear from every earlier step. `LeadFunnelService` reads the furthest rung the
  pipeline stage, the derived flags and the touch history can evidence, which also makes the funnel
  monotone: a step can never hold more leads than the step above it.
- **A drop is not automatically a loss.** Between two steps the leads that did not advance are split
  into *lost* (a terminal stage, with the reason the team recorded) and *still open* (in play, just
  not there yet). Reading them together would report a young pipeline's work in progress as churn.
- **Every step carries both money figures** — the couples' wedding budgets and Wedding Planner's planning fee on
  them — and the widget's toggle switches which one the whole chart is denominated in. An inquiry
  with no stated budget counts as a lead worth zero, so a step's average is over every lead on it.

## Demo data

`MarketingDemoSeeder` generates a deterministic year of inquiries when the dataset is still the
original six, and never deletes a lead. Turn it off with `planner.marketing.demo-history=false` (the
inbox and merge test suites do exactly that, since they assert against the six hand-written ones).
Every lead is also imported into Conversations, so a fuller year means a fuller inbox.

The Events half of the overview page (invoiced, received, confirmed events, events by stage) reads
zero until the events example data is seeded with `planner.events.demo=true`. That flag also fills the
Events list with twenty weddings named after their couples — most built from the booked leads
themselves, so the pipeline and the Events list agree about who booked — each with its own estimate
and, where the wedding has happened or is this year's work, posted invoices and payments. See
[docs/events.md](docs/events.md#the-book-of-weddings).

## Assumptions to validate

- An estimate row with no cost entered is passed through at the quoted price. This under-reports
  margin on purpose: the alternative reads an uncosted estimate as pure profit.
- Commission is charged on what a supplier is paid, not on what the couple is quoted, and a
  refundable deposit earns none.
- The demo commission rates (12% florals, 10% photo and film, 8% venue) are plausible placeholders,
  not Wedding Planner's real terms — they are the first thing to replace with the actual deals.
- Margin is gross: before tax and before Wedding Planner's own overheads.
- Target threshold: €300k.
- VIP threshold: €500k.
- Planning fee: 12% of the wedding budget. Every cost-per-booking and return figure depends on it.
- Last-touch credit for channel cost, first-touch credit for demand creation.
- Revenue is credited to the month the inquiry arrived, not the month the contract was signed.
- A lead requires budget, date, and preferred location to be automatically qualified.
- VIP goes to a founder; other target leads go to a manager.
- `MarketingPerformance` is a presale read model, not the future attribution ledger.

## Framework sources inspected

- ONNO source: <https://github.com/onno-erp/onno-framework> at tag `v3.0.0`
- Documentation: <https://docs.onno.su/>
- Framework architecture: `../onno-framework/docs/ARCHITECTURE.md`
- UI reference: `../onno-framework/onno-ui-starter/README.md`
- Example app: `../onno-framework/example`

## CRM in 3.0.0

The official `onno-crm-starter` is bound to the app's `Contacts` catalog. The existing inquiry
qualification, founder routing, pipeline and marketing dashboard remain Wedding Planner business logic.
See the [official CRM module contract](https://github.com/onno-erp/onno-framework/blob/v3.0.0/onno-crm-starter/README.md).

- **Inbox:** searchable conversation history, contact details, unread tracking and
  internal notes, available to the existing `MANAGER` demo account. The rail opens the inbox directly, without a submenu.
- **Sales → Contacts:** ordinary editable contacts, linked from inquiries and inbox conversations.
- Existing active inquiries are imported on startup. Each gets a contact unless one is already
  selected, one conversation, and its original message when present. Stable import IDs prevent
  duplicate history across restarts; deleted conversations are not resurrected. Imports are transactional.
- New inquiries can be imported immediately using **Add to inbox** on their detail screen. Choose
  an existing contact first to group multiple inquiries under the same contact. Unimported inquiries
  are also picked up on the next startup. Later inquiry edits do not rewrite imported messages or contacts.
- No automatic matching by name or email occurs. When a future connector encounters an unknown
  channel identity, the configured inbound callback creates a distinct contact.
- Imported conversations belong to local inquiry archives. External delivery is **not connected**;
  replies are disabled, while internal notes work. No messages are sent to Instagram, WhatsApp or email.
  Real channel accounts and identity links require a separate connector integration.
- Inbox membership grants access through the scoped CRM API. Generic CRM storage endpoints remain
  restricted to administrators. Sales owners remain the existing manager/founder routing field;
  inbox agent assignment is not enabled without an employee identity catalog.

## Upgrade and verification

All `su.onno` dependencies use 3.0.0. The build uses Maven Central and the official
`https://cloud.onno.su/modules` registry, restricted to `su.onno`, because the CRM 3.0.0 artifact was
not yet available from Central when this upgrade was implemented. No registry credentials are needed
for these open-source artifacts.

The migration adds CRM/contact tables and a nullable `contact` reference on inquiries; existing
inquiry fields and marketing records are retained. Back up the H2 database while the app is stopped
before upgrading an existing installation.

```bash
# From the sibling framework directory; uses published artifacts, not framework source dependencies.
./gradlew -p ../wedding-planner-demo clean test bootJar
./gradlew -p ../wedding-planner-demo bootRun
```

Tests cover qualification, repeated imports, contact reuse, blank messages and deleted records.
Runtime version is reported by `/api/config` in `update.current`.

### First-contact phone calls

The **New communication** button on the right of the inbox channel toolbar opens a form that records a phone call for a new or existing client.
Enter a name, international phone number and call notes. Matching ignores phone punctuation and
normalizes the `00` international prefix; a unique active contact is reused without renaming it.
If multiple contacts share a number, select the caller. Local numbers without a country code are
not guessed. Calls use a dedicated Phone calls inbox and appear as internal call cards in the
unified timeline; recording a call does not send a message or place a telephone call.

`GET /api/planner/communications/matches?phone=...` returns writable matching contacts.
`POST /api/planner/communications` accepts `requestId` (UUID), `name`, `phone`, `notes`, and optional
`contactId`, returning `contactId`, `conversationId`, and `activityId`. Workspace/contact write
permissions, read-only mode, and CSRF apply. Contact, conversation and activity saves share one
transaction; the CRM guard serializes matching/creation and request IDs make retries idempotent.
