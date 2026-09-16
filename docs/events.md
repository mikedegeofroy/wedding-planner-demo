# Events and budgets

The Events section uses the host application's ONNO model and its existing CRM contacts.
All client receipts and contractor payments pass through Wedding Planner.

- **Event** is the project: primary client, dates, venue/location, guest count, stage, participants and selected estimate.
- **Estimate** is a versioned scenario with phase, article, details, contractor, charge type, price status, quantity, the unit amount quoted to the couple and, where it differs, the unit cost Wedding Planner pays. Only one scenario is selected; alternatives are never added together.
- **Invoice** is a document with header (event, counterparty, direction, due date, external number) and tabular line items. Client and supplier invoices use the same structure. Posting records charges and outstanding debt.
- **Payment** is a separate document linked to a posted invoice. Posting reduces its debt and records cash received from a client or paid to a contractor. Partial payments are supported. Overpayment is rejected; a paid invoice cannot be unposted until its payments are unposted.

## Demo

Open Events → Events, then an event. The financial workspace appears below the event fields.
Compare the scenarios in the grid and use **Use this estimate** to pick the one the event runs on. **Open estimate** edits the native ONNO table. Assign a contractor and a positive quoted amount to a row, then select its article below the native estimate list; **Create expense** prepares a supplier invoice draft from that row. Review the draft and post it. Open a posted invoice and choose **Record payment** to open a payment prefilled with that invoice. Write saves a draft; Post affects totals.

Participants — the couple, the suppliers, the venue and the planners running the wedding — are linked from the event's **Participants** related list; the primary client is also available in the event header. A participant's role is the inbox folder their contact card is filed under, so choosing the role first narrows the contact picker to that folder instead of offering every card in the catalog. Contact merging updates active event/participant/budget references and draft invoices. Posted invoices retain the original counterparty for audit.

## Imported examples

`--planner.events.demo=true` imports two events and five estimate scenarios from `events/budget-examples.json`. Import is opt-in and idempotent. Existing records are never overwritten. No client, event date, invoice or payment is invented.

The same flag staffs each event with demo suppliers, gives them a standing commission rate, and puts
them behind the estimate articles that are obviously theirs — the florist on the flowers, the studio
on the photo and film, the venue on the rentals — so the margin panel has something to report. **No
price is touched**: an article is only given an owner. A line that already names a contractor, and a
rate already edited in the demo, are both left alone. Databases seeded before this was added keep
their existing rows; delete `data/` to import them afresh.

The first PDF's two scenario totals are EUR 736,600 and EUR 461,600, VAT included. The EUR 35,000 refundable Balbiano deposit is separate. Guest accommodation marked zero but described as TBD remains unpriced.

The second PDF's totals are EUR 751,395, EUR 767,520 and EUR 1,035,825. Its blanket VAT basis is not stated; the original quoted amounts and comments are retained. TBD, included and not required are distinct states. A known subtotal is not a fully confirmed budget.

## The book of weddings

`--planner.events.demo=true` fills the Events list with twenty weddings, each named after the couple
("Giulia & Tommaso") rather than after a venue and a headcount. They come from three places, and
which one a wedding came from is visible on it:

- **Booked inquiries.** An event is a lead that said yes, so most of the book is built from the
  booked leads themselves: the couple's own name, their stated date, region, guest count and budget,
  and the contact card the inquiry already created as the event's client. The Events list and the
  pipeline therefore agree about who booked.
- **Earlier seasons.** The demand history covers one year and a wedding booked inside it has not
  happened yet, so a book built only from leads is a business that has never delivered a wedding.
  Six couples whose inquiries predate the history fill that gap; their contact cards are created
  here, as two people linked as partners, exactly as a split couple card ends up.
- **The two imported PDF examples**, which keep no client and no date — that is what makes them
  examples.

Every couple's wedding carries an estimate of its own: an imported example's articles and comments,
re-priced so the known subtotal lands near the budget that couple stated, trimmed to the days the
wedding actually runs (about two in five are the day itself plus planning), and stripped of the
example's own figures — a comment is kept only down to its first priced line, and an article named
after another villa does not travel. Price status is carried across, so an article the example never
priced stays unpriced here.

**Only the two imported examples carry amounts anybody actually quoted.** Everything else is demo
data, and each event's notes say so. Venue names outside the imported examples are invented.

A wedding's stage is its date: past weddings are completed, the next twelve months are confirmed,
and anything beyond that is still planning. Set `planner.events.demo-events` to seed a different number
(default 20, counting the imported examples).

### The money on them

`EventLedgerSeeder` posts the ledger those weddings would have, because an estimate with nothing
committed against it cannot demonstrate the question the event page exists to answer:

| Stage | Suppliers | Client |
| --- | --- | --- |
| Completed | every contractor-owned article invoiced and paid in full | invoiced for the package, received in full |
| Confirmed | the first two contractors committed, 40% paid as deposits | invoiced, 30% received |
| Planning | nothing | nothing |

Every document is posted — a draft moves nothing — and each is keyed by a stable id, so a restart
neither doubles the ledger nor undoes an edit made in the demo.

## What the event earns

Wedding Planner earns from three unrelated places, and **Events → an event → Margin** reports them apart rather
than as one blended number, because which of them is carrying an event is the finding:

- **Agency fee** — the planning fee charged in the open, on the estimate and on the client invoice.
  An estimate row is a fee when its charge type says so; the imported examples name it outright
  ("wedding planner's fee", "organization and coordination of the wedding day"), so those rows are
  classified on import rather than added.
- **Markup** — the quoted unit amount less the unit cost on the same estimate row. **A blank cost is
  read as a pass-through, never as margin**: a row with no cost entered costs what it is quoted, so
  an estimate nobody has costed reports zero markup instead of mistaking the wedding budget for
  profit.
- **Commission** — what a supplier rebates to Wedding Planner out of their own invoices. The rate lives on the
  supplier's contact card as their standing terms, and an event that agreed a different rate
  overrides it on that supplier's participant row. It is charged on what the supplier is paid, not
  on what the couple is quoted, and appears on no client document.

Every figure is reported twice. **Forecast** reads the selected estimate — what the event earns if
it runs as quoted, and the number worth planning against. **Booked** reads posted invoices only;
drafts move nothing. The two disagree for most of an event's life because client and supplier
invoicing run on different schedules, so the panel says outright when booked is not yet comparable
and only calls it the event's margin once both sides are invoiced at least to the estimate.

Refundable deposits are excluded from both columns and from every commission base: money held and
returned is not revenue, not cost, and earns nobody a rebate.

This is the one figure on the event page that is never turned towards a couple. It is served by
`GET /api/planner/events/{id}/margin` behind its own role list, `planner.events.margin-roles`
(default `MANAGER,ADMIN`), so narrowing it removes the tab and refuses the endpoint rather than
merely hiding a panel. Amounts follow the invoices, which are gross totals — the margin is
before tax and before Wedding Planner's own overheads.

## Where the money goes

The event page leads with the estimate's **detail**, not with a percentage. Each budget category
opens to the articles under it: the position, the comments the venue actually wrote, the contractor
who owns it, the event day, the charge type, and the quoted amount — and beside those, what has been
committed to a supplier against that article and how much of it is paid. Under a committed line sit
the supplier invoices that committed it, each one openable, so "€40,000 committed" can be traced
rather than trusted.

Two things the category totals alone cannot say are stated outright:

- **Unbudgeted spend.** A supplier invoice booked against an article the chosen scenario does not
  carry gets its own row rather than disappearing into a category total.
- **Unpriced articles.** They are counted per category and excluded from the total, because a known
  subtotal is not a confirmed budget.

The breakdown follows the scenario being read, not only the one driving the event's totals, so an
alternative can be opened up before it is chosen. It is served by
`GET /api/planner/events/{id}/workspace?budget=<estimate>`. While an alternative is being read the
headline tile says **Estimate being read**, so a figure from a scenario the event does not run on is
never mistaken for the event's own.

## Comparing the scenarios

The Estimates tab lays the quotes out as a grid — a column per estimate, a row per quoted position —
served by `GET /api/planner/events/{id}/scenarios`, the same
`EventBudgetReport.comparison` the scenario workbook is written from, so the screen and the
sheet cannot drift apart. Positions are folded into their categories; opening one compares it venue
by venue. The cheapest amount on a row is marked, which is a prompt to ask why rather than an
instruction to take it.

Choosing happens from the grid: each column carries its own total and its own **Use this estimate**,
and choosing one also switches the page to reading it, so the totals above and the expenses below
follow the decision immediately.

## Excel export

Estimates are argued over and countersigned in a spreadsheet, so both shapes export as real `.xlsx`
workbooks — column widths, wrapped comments, a frozen header, a euro number format — rather than a
CSV that loses them.

- **Export to Excel** — `GET /api/planner/events/budgets/{id}/export`. Reached from the estimate
  record; the event page itself offers only the scenario export, because a planner comparing quotes
  wants the grid, not one venue's sheet. One sheet of every article with what it covers, its
  contractor, charge type, status, quantity, unit price, estimated, committed and paid, banded and
  subtotalled by category, with the supplier invoices indented under their article; plus a
  **By category** sheet matching the table on screen.
- **Export scenarios** — `GET /api/planner/events/{id}/scenarios/export`. Every scenario side by side
  in the layout the venue quotes arrive in: category, position, comments, event day, charge type,
  then a money column per scenario and a total at the foot. A row is keyed on the position, so a
  line quoted by three venues reads across; where a scenario has no price the cell carries its
  status ("To be confirmed", "Included", "Not required") exactly as the PDFs do, and where the
  venues wrote different comments the sheet says the first one is shown.

Both require the same read access as the event workspace and stream the workbook as an attachment.
Neither carries cost, markup or commission: the exports are documents a couple may see, and margin
is deliberately not one of them.

## Boundaries

This prototype records invoices and payments internally. It does not send legal invoices, execute bank transfers, reconcile bank feeds, calculate tax returns or export signed client estimates. Amounts on invoices are gross totals; the cash balance is not profit. Refundable deposits are separately classified invoice lines and are not automatically expensed. Refunds and credit notes need a later workflow.

## Verification

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ../onno-crm-inbox/gradlew test bootJar`

Seeder tests cover the comment cleaner (a comment led by the example's price is dropped, a priced
list goes entirely, a descriptive lead survives) and the date-driven stage. Budget report tests cover the article-level breakdown naming its contractor and invoices, category
totals reconciling with the lines under them, unbudgeted spend getting its own row, and both
workbooks reconciling to the estimate totals they were cut from. Finance integration tests cover
source total reconciliation, unknown prices, row-to-invoice mapping, access checks, event-bound scenario selection, draft exclusion, receipts, partial payments, overpayment rollback and unposting dependencies. Margin integration tests cover the pass-through default earning nothing, fee/markup/commission reported apart and adding up, the event rate beating the supplier's standing one without rewriting it, deposits earning nothing on either side, drafts moving nothing, booked admitting when it is incomparable, and the separate role gate. Widget TypeScript is checked with `build/onno-widgets/node_modules/.bin/tsc --noEmit -p src/main/widgets/tsconfig.json`.

Run a copy of the packaged jar from an immutable path; do not launch the mutable `build/libs` jar. Preview DB backups are in `../tmp/native-crm-smoke`.
