import {Card,CardContent,CardHeader,CardTitle,Segmented,Select,SelectContent,SelectItem,SelectTrigger,SelectValue,
 registerWidget,useCallback,useEffect,useMemo,useRef,useState,useWidgetUpdates,type WidgetProps} from '@onno/widget-sdk';

/**
 * The acquisition funnel, with the money on it.
 *
 * <p>The pages around this widget report the funnel as four separate charts — a count of qualified
 * leads here, a booking rate there — and leave the reader to subtract one from the other to find
 * where couples are actually being lost. This draws the whole ladder at once: how many leads reached
 * each step, what they were worth, and for every gap between two steps, how many fell out, how many
 * are merely still in flight, and what walked out of the door with them.</p>
 *
 * <p>Two things it deliberately does not do. It never reads a drop as a loss: leads that have not
 * advanced are split into <b>lost</b> (a terminal stage, with the team's own reason) and <b>still
 * open</b>, because reporting a young pipeline's work-in-progress as churn is the standard way a
 * funnel chart lies. And it never invents a stage a lead did not reach — the server places each lead
 * at the furthest rung its stage, flags and touch history can evidence, so the ladder is monotone and
 * a late loss still counts on every step it passed.</p>
 */

/* ----- the shared dashboard window ---------------------------------------------------------- */

type TimeRange={kind:'relative';amount:number;unit:string}|{kind:'absolute';from?:string;to?:string}|{kind:'all'};
const RANGE_KEY='onno.dashboard.timeRange';
const MS:Record<string,number>={s:1e3,m:6e4,h:36e5,d:864e5,w:6048e5,M:2592e6,y:31536e6};

/**
 * The dashboard's period, read from where the host persists its picker. The host does not yet hand
 * the selected range to plugin widgets, and a widget with a second period control of its own would
 * let one board report two different months side by side — so the value is read back and polled for
 * changes rather than duplicated. A dashboard with no picker on it falls back to the host's default.
 */
function useDashboardRange():TimeRange{
 const read=()=>{try{return localStorage.getItem(RANGE_KEY)??'';}catch{return '';}};
 const [raw,setRaw]=useState(read);
 useEffect(()=>{
  const tick=()=>setRaw(current=>{const next=read();return next===current?current:next;});
  const timer=window.setInterval(tick,1000);
  window.addEventListener('storage',tick);
  return()=>{window.clearInterval(timer);window.removeEventListener('storage',tick);};
 },[]);
 return useMemo(()=>{
  try{const parsed=JSON.parse(raw||'null');if(parsed&&typeof parsed.kind==='string')return parsed as TimeRange;}catch{/* ignore */}
  return {kind:'relative',amount:30,unit:'d'};
 },[raw]);
}

/** The window as the endpoint takes it: local ISO bounds, either of which may be absent. */
function bounds(range:TimeRange):{from?:string;to?:string}{
 if(range.kind==='all')return {};
 if(range.kind==='absolute')return {from:range.from||undefined,to:range.to||undefined};
 const span=(MS[range.unit]??MS.d)*range.amount;
 return {from:new Date(Date.now()-span).toISOString()};
}

/* ----- the payload -------------------------------------------------------------------------- */

type Rung={key:string;label:string;hint:string;leads:number;value:number;fee:number};
type Reason={key:string;label:string;color:string|null;leads:number;value:number;fee:number};
type Gap={fromKey:string;toKey:string;label:string;lost:number;open:number;lostValue:number;lostFee:number;reasons:Reason[]};
type Segment={key:string;label:string;color:string|null;leads:number;booked:number;bookedValue:number;bookedFee:number;
 cost:number;returnOnSpend:number|null;reached:number[]};
type Funnel={from:string|null;to:string|null;leads:number;dimension:string;dimensionLabel:string;segment:string|null;
 rungs:Rung[];gaps:Gap[];segments:Segment[]};
type Payload={funnel:Funnel;splits:{key:string;label:string}[]};

/* ----- numbers ------------------------------------------------------------------------------ */

const compact=new Intl.NumberFormat('en-GB',{notation:'compact',maximumFractionDigits:1});
const exact=new Intl.NumberFormat('en-GB',{maximumFractionDigits:0});
// Compact past five figures, exact below it — and en-GB's own lower-case "80.3m €", because that is
// what the KPI tiles on the same board print and a capital M here would read as a different number.
// The € trails the number, as it does everywhere else on these pages.
const money=(n:number)=>`${Math.abs(n)>=10000?compact.format(n):exact.format(n)} €`;
const count=(n:number)=>new Intl.NumberFormat('en-GB').format(n);
const percent=(part:number,whole:number)=>whole<=0?'—':`${Math.round((part/whole)*100)}%`;
// Return is printed as a multiple rather than a percentage: "6.2×" is how the marketing page and the
// trade read return on ad spend, and "620%" invites being read as a growth rate. Past a hundredfold
// the exact figure stops meaning anything — it says the spend behind those bookings was immaterial,
// not that the channel is 2,899 times better than the one above it — so it reads as ">100×" and the
// cell's tooltip carries the fee and the spend it came from.
const multiple=(value:number|null)=>value==null?'—'
 :value>=100?'>100×'
 :`${new Intl.NumberFormat('en-GB',{maximumFractionDigits:value>=10?0:1}).format(value)}×`;

/* ----- geometry ----------------------------------------------------------------------------- */

const ROW=40;      // a rung's band
const GAP=36;      // the space between two rungs, where the drop-off is drawn
const PAD=10;

/** Ordinal ramp: one hue, deepening down the funnel, so the steps read as a sequence and not as six
 *  unrelated categories. Text never wears these — every figure on the chart is in an ink token. */
const band=(index:number,total:number)=>`hsl(var(--chart-1) / ${(0.4+(0.6*index)/Math.max(total-1,1)).toFixed(2)})`;

/** Figures on the chart wear ink tokens, never a series colour; only the marks carry identity. */
const INK={fill:'hsl(var(--foreground))',fontSize:12,fontWeight:500} as const;
const FIGURE={...INK,fontWeight:600,fontVariantNumeric:'tabular-nums'} as const;
const MUTED={fill:'hsl(var(--muted-foreground))',fontSize:10} as const;
const MUTED_FIGURE={...MUTED,fontVariantNumeric:'tabular-nums'} as const;

interface Tip{x:number;y:number;title:string;rows:[string,string][];note?:string}

/**
 * Where the tooltip sits. The card clips its own overflow, so one that would be drawn above the
 * first rung flips below the pointer rather than being cut in half by the card's edge, and it is
 * held clear of both side walls.
 */
function place(tip:Tip,width:number):Record<string,string|number>{
 const left=Math.min(Math.max(tip.x,110),Math.max(width-110,110));
 return tip.y<96
  ?{left,top:tip.y+18,transform:'translate(-50%,0)'}
  :{left,top:tip.y-12,transform:'translate(-50%,-100%)'};
}

function LeadFunnelWidget({widget}:WidgetProps){
 const range=useDashboardRange();
 // Resolved once per selected period, not once per render: a rolling "last 90 days" recomputed on
 // every render would be a new `from` every time, and the fetch effect would never stop firing.
 const {from,to}=useMemo(()=>bounds(range),[range]);
 const [basis,setBasis]=useState<'value'|'fee'>('value');
 const [by,setBy]=useState(widget.extraConfig?.by??'source');
 // The slice the ladder is narrowed to. A key belongs to one dimension's rows, so changing the split
 // clears it rather than sending last dimension's channel name against this one's grouping.
 const [slice,setSlice]=useState<string|null>(null);
 const chooseSplit=(value:string)=>{setSlice(null);setBy(value);};
 const [data,setData]=useState<Payload|null>(null);
 const [error,setError]=useState('');
 const [tip,setTip]=useState<Tip|null>(null);
 const [width,setWidth]=useState(720);
 const host=useRef<HTMLDivElement|null>(null);

 // The chart is drawn at real pixel width rather than scaled from a fixed viewBox, so its labels
 // stay the size of the rest of the product on a wide board and on a phone alike.
 useEffect(()=>{
  const element=host.current;if(!element||typeof ResizeObserver==='undefined')return;
  const measure=(value:number)=>{if(value>0)setWidth(Math.max(value,280));};
  measure(element.clientWidth);
  const observer=new ResizeObserver(entries=>measure(entries[0]?.contentRect.width??0));
  observer.observe(element);return()=>observer.disconnect();
 },[]);

 const load=useCallback(async()=>{
  const query=new URLSearchParams({by});
  if(slice)query.set('segment',slice);
  if(from)query.set('from',from);
  if(to)query.set('to',to);
  const response=await fetch(`/api/planner/funnel?${query}`,{credentials:'same-origin'});
  if(!response.ok)throw new Error(response.status===403?'You cannot read the lead funnel.':'The funnel is unavailable.');
  setData(await response.json() as Payload);
 },[by,slice,from,to]);

 useEffect(()=>{let live=true;setError('');
  void load().catch(e=>{if(live)setError((e as Error).message);});
  return()=>{live=false;};
 },[load]);
 // A lead that books while the board is open moves the funnel under the reader's eyes.
 useWidgetUpdates(widget,()=>{void load().catch(()=>{/* the next successful read repairs it */});});

 const funnel=data?.funnel;
 const rungs=funnel?.rungs??[];
 const top=rungs[0]?.leads??0;
 const amount=(rung:{value:number;fee:number})=>basis==='fee'?rung.fee:rung.value;

 // Label gutters: the two number columns shrink on a narrow board rather than crushing the funnel.
 const narrow=width<560;
 const left=narrow?92:132;
 const right=narrow?92:136;
 const plot=Math.max(width-left-right,90);
 const centre=left+plot/2;
 const height=PAD*2+rungs.length*ROW+Math.max(rungs.length-1,0)*GAP;
 const bandWidth=(leads:number)=>top<=0?0:Math.max((plot*leads)/top,leads>0?5:0);
 const rowY=(index:number)=>PAD+index*(ROW+GAP);

 const show=(event:{clientX:number;clientY:number},tooltip:Omit<Tip,'x'|'y'>)=>{
  const box=host.current?.getBoundingClientRect();
  setTip({...tooltip,x:event.clientX-(box?.left??0),y:event.clientY-(box?.top??0)});
 };

 const splits=data?.splits??[];
 // The selected row's own figures, for the chip and the empty state. The server always returns every
 // slice of the period, so this resolves even while the ladder below is narrowed to one of them.
 const active=funnel?.segment?funnel.segments.find(segment=>segment.key===funnel.segment)??null:null;

 return <Card className="overflow-hidden">
  {/* No responsive variants here on purpose: the widget stylesheet is injected before the host's,
      so a `sm:`-prefixed utility of ours loses to the host's plain one at the same specificity.
      Wrapping does the same job at every width and cannot be out-cascaded. */}
  <CardHeader className="flex-row flex-wrap items-start justify-between gap-2 space-y-0 p-4 pb-1">
   <div className="min-w-[220px] flex-1">
    <CardTitle className="text-[13px] font-medium">{widget.title||'Lead funnel'}</CardTitle>
    {widget.hint&&<p className="pt-0.5 text-[11px] text-muted-foreground">{widget.hint}</p>}
   </div>
   <div className="flex flex-wrap items-center gap-1.5">
    <Segmented size="sm" value={basis} onChange={(value:string)=>setBasis(value as 'value'|'fee')}
     options={[{value:'value',label:'Wedding budget'},{value:'fee',label:'Planner fee'}]}/>
    {active&&<button type="button" onClick={()=>setSlice(null)}
     className="inline-flex h-7 items-center gap-1.5 rounded-pill border border-border bg-muted/50 px-2.5 text-xs text-foreground"
     title={`Showing ${active.label} only — clear to read every ${funnel?.dimensionLabel.toLowerCase()??'lead'}`}>
     <span className="inline-block size-2 rounded-sm"
      style={{background:active.color??'hsl(var(--muted-foreground) / 0.5)'}}/>
     {active.label}<span aria-hidden="true" className="text-muted-foreground">✕</span>
     <span className="sr-only">Clear the channel filter</span>
    </button>}
    {splits.length>0&&<Select value={by} onValueChange={chooseSplit}>
     {/* The trigger states its own label: on a phone the host renders the options in a drawer that
         is unmounted while closed, and a bare SelectValue has nothing to mirror. */}
     <SelectTrigger className="h-7 w-[150px] text-xs"><SelectValue>
      {`By ${(splits.find(split=>split.key===by)?.label??'first touch').toLowerCase()}`}</SelectValue></SelectTrigger>
     <SelectContent>{splits.map(split=><SelectItem key={split.key} value={split.key} className="text-xs">
      {`By ${split.label.toLowerCase()}`}</SelectItem>)}</SelectContent>
    </Select>}
   </div>
  </CardHeader>

  <CardContent className="p-4 pt-2">
   {/* The measured element is always mounted: hang it off the chart branch alone and the first
       measurement never happens, because that branch does not exist while the funnel is loading. */}
   <div ref={host} className="relative w-full">
   {error?<p role="alert" className="py-6 text-center text-xs text-muted-foreground">{error}</p>
   :!funnel?<p role="status" className="py-6 text-center text-xs text-muted-foreground">Reading the funnel…</p>
   :top===0?<p className="py-6 text-center text-xs text-muted-foreground">
     {active?`No ${active.label} inquiries arrived in this period.`:'No inquiries arrived in this period.'}</p>
   :<>
    <svg width={width} height={height} role="img" className="block max-w-full"
     aria-label={`${active?`${active.label}: f`:'F'}unnel from ${count(top)} inquiries to ${count(rungs[rungs.length-1].leads)} bookings`}>
     {rungs.map((rung,index)=>{
      const w=bandWidth(rung.leads);const y=rowY(index);const gap=funnel.gaps[index];
      const dropped=gap?gap.lost+gap.open:0;
      const dropW=bandWidth(dropped);
      const lostW=dropped>0?(dropW*gap.lost)/dropped:0;
      const openW=Math.max(dropW-lostW-(lostW>0&&gap.open>0?2:0),0);   // 2px of surface between segments
      const next=rungs[index+1];
      return <g key={rung.key}>
       {/* the taper between this rung and the next: the funnel's own wall */}
       {next&&<path d={`M${centre-w/2} ${y+ROW} L${centre+w/2} ${y+ROW} L${centre+bandWidth(next.leads)/2} ${y+ROW+GAP} L${centre-bandWidth(next.leads)/2} ${y+ROW+GAP} Z`}
        fill="hsl(var(--chart-1) / 0.09)"/>}

       <rect x={centre-w/2} y={y} width={w} height={ROW} rx={4} fill={band(index,rungs.length)}/>
       {/* a hit target the width of the row, so the tooltip is not a game of aiming at a thin band */}
       <rect x={left} y={y} width={plot} height={ROW} fill="transparent"
        onMouseMove={e=>show(e,{title:rung.label,note:rung.hint,rows:[
         ['Leads',`${count(rung.leads)} · ${percent(rung.leads,top)} of inquiries`],
         ['Wedding budgets',money(rung.value)],
         ['Wedding Planner planning fee',money(rung.fee)],
         ['Average per lead',rung.leads>0?money(amount(rung)/rung.leads):'—']]})}
        onMouseLeave={()=>setTip(null)}/>

       <text x={left-12} y={y+ROW/2-1} textAnchor="end" style={INK}>{rung.label}</text>
       <text x={left-12} y={y+ROW/2+13} textAnchor="end" style={MUTED}>
        {index===0?'of the period':`${percent(rung.leads,rungs[index-1].leads)} of the step above`}</text>

       <text x={width-right+12} y={y+ROW/2-1} style={FIGURE}>{count(rung.leads)}</text>
       <text x={width-right+12} y={y+ROW/2+13} style={MUTED_FIGURE}>
        {money(amount(rung))}{!narrow&&rung.leads>0?` · ${money(amount(rung)/rung.leads)} avg`:''}</text>

       {gap&&dropped>0&&<g>
        <text x={centre} y={y+ROW+15} textAnchor="middle" style={MUTED_FIGURE}>
         {`−${count(dropped)} (${percent(dropped,rung.leads)})`}
         {gap.lost>0?` · ${count(gap.lost)} lost, ${money(basis==='fee'?gap.lostFee:gap.lostValue)} gone`:''}
         {gap.open>0?` · ${count(gap.open)} still open`:''}
        </text>
        {gap.lost>0&&<rect x={centre-dropW/2} y={y+ROW+21} width={lostW} height={7} rx={3}
         fill="hsl(var(--destructive) / 0.85)"/>}
        {gap.open>0&&<rect x={centre-dropW/2+(gap.lost>0?lostW+2:0)} y={y+ROW+21} width={openW} height={7} rx={3}
         fill="hsl(var(--muted-foreground) / 0.35)"/>}
        <rect x={left} y={y+ROW} width={plot} height={GAP} fill="transparent"
         onMouseMove={e=>show(e,{title:gap.label,rows:[
          ['Did not advance',`${count(dropped)} · ${percent(dropped,rung.leads)} of this step`],
          ['Lost',`${count(gap.lost)} · ${money(basis==='fee'?gap.lostFee:gap.lostValue)}`],
          ['Still open',count(gap.open)],
          ...gap.reasons.map(reason=>[`  ${reason.label}`,
           `${count(reason.leads)} · ${money(basis==='fee'?reason.fee:reason.value)}`] as [string,string])]})}
         onMouseLeave={()=>setTip(null)}/>
       </g>}
      </g>;
     })}
    </svg>

    {/* Identity is never colour alone: the two drop-off marks are named, and every figure above is
        also written out beside the chart it belongs to. */}
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-1 text-[10px] text-muted-foreground">
     <span className="inline-flex items-center gap-1.5">
      <span className="inline-block size-2 rounded-sm" style={{background:'hsl(var(--chart-1) / 0.85)'}}/>Reached the step</span>
     <span className="inline-flex items-center gap-1.5">
      <span className="inline-block size-2 rounded-sm" style={{background:'hsl(var(--destructive) / 0.85)'}}/>Lost</span>
     <span className="inline-flex items-center gap-1.5">
      <span className="inline-block size-2 rounded-sm" style={{background:'hsl(var(--muted-foreground) / 0.35)'}}/>Still open</span>
     <span>{basis==='fee'?'Money shown is Wedding Planner’s planning fee (12% of the wedding budget).':'Money shown is the couples’ wedding budgets.'}</span>
    </div>

    {funnel.segments.length>1&&<Breakdown funnel={funnel} basis={basis}
      onSelect={key=>setSlice(current=>current===key?null:key)}/>}

    {tip&&<div role="tooltip"
     className="pointer-events-none absolute z-50 w-max max-w-[260px] rounded-panel border border-border bg-card p-2 text-[11px] shadow-md"
     style={place(tip,width)}>
     <div className="pb-1 text-[11px] font-semibold text-foreground">{tip.title}</div>
     {tip.rows.map(([label,value])=><div key={label} className="flex justify-between gap-3">
      <span className="whitespace-pre text-muted-foreground">{label}</span>
      <span className="font-medium tabular-nums text-foreground">{value}</span></div>)}
     {tip.note&&<p className="pt-1 text-[10px] text-muted-foreground">{tip.note}</p>}
    </div>}
   </>}
   </div>
  </CardContent>
 </Card>;
}

/**
 * The same ladder, one row per slice of the chosen dimension. A channel that sends volume and loses
 * it at the meeting is a different problem from one that sends few couples and books most of them,
 * and the overall funnel cannot tell them apart — the glyph is each slice's own shape, so the rows
 * compare as shapes rather than as sizes.
 *
 * <p>A row is also the way into that channel's own funnel: picking one redraws the ladder above for
 * its leads alone, with the same drop-off and loss reasons. Shapes say which channel leaks; the
 * ladder says where.</p>
 *
 * <p><b>Return</b> is the planning fee those leads booked per euro of the channel spend they carry —
 * the fee rather than the couples' budgets, since the budgets are the clients' money and not what
 * the advertising bought. Channels that cost nothing (referral, organic, press) are blank rather
 * than infinite: the ratio does not apply to them.</p>
 */
function Breakdown({funnel,basis,onSelect}:{funnel:Funnel;basis:'value'|'fee';onSelect:(key:string)=>void}){
 const labels=funnel.rungs.map(rung=>rung.label);
 return <div className="pt-3">
  <div className="flex flex-wrap items-center justify-between gap-x-3 pb-1">
   <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/80">By {funnel.dimensionLabel.toLowerCase()}</h4>
   <span className="text-[10px] text-muted-foreground">
    {funnel.segment?'Pick a row to read its own funnel above':`${labels[0]} → ${labels[labels.length-1]}`}</span>
  </div>
  <div className="overflow-x-auto">
  <table className="w-full min-w-[470px] border-collapse text-[11px]">
   <thead><tr className="text-muted-foreground">
    <th className="py-1 text-left font-normal">{funnel.dimensionLabel}</th>
    <th className="py-1 text-left font-normal">Shape</th>
    <th className="py-1 text-right font-normal">Leads</th>
    <th className="py-1 text-right font-normal">Booked</th>
    <th className="py-1 text-right font-normal">Rate</th>
    <th className="py-1 text-right font-normal">{basis==='fee'?'Fee booked':'Value booked'}</th>
    <th className="py-1 text-right font-normal" title="Planning fee booked per € of the channel spend these leads carry">Return</th>
   </tr></thead>
   <tbody>{funnel.segments.map(segment=>{
    const head=segment.reached[0]||0;
    const chosen=segment.key===funnel.segment;
    return <tr key={segment.key} onClick={()=>onSelect(segment.key)}
     className={`cursor-pointer border-t border-border/60 ${chosen?'bg-accent/40':'hover:bg-muted/40'}`}>
     <td className="max-w-[180px] py-1.5 pr-2 text-foreground">
      {/* A real control, not a clickable row alone: this is how the filter is reached by keyboard,
          and aria-pressed is what says the ladder above is currently narrowed to this slice. */}
      <button type="button" aria-pressed={chosen} title={segment.label}
       onClick={event=>{event.stopPropagation();onSelect(segment.key);}}
       className="flex max-w-full items-center gap-1.5 truncate text-left text-foreground">
       <span className="inline-block size-2 shrink-0 rounded-sm"
        style={{background:segment.color??'hsl(var(--muted-foreground) / 0.5)'}}/>
       <span className="truncate">{segment.label}</span></button></td>
     <td className="py-1.5">
      <span className="flex h-6 items-end gap-[2px]">{segment.reached.map((reached,index)=>
       <span key={index} title={`${labels[index]}: ${count(reached)}`}
        className="inline-block w-2 rounded-sm"
        style={{height:`${Math.max(head>0?(reached/head)*24:0,1)}px`,background:`hsl(var(--chart-1) / ${(0.4+(0.6*index)/Math.max(segment.reached.length-1,1)).toFixed(2)})`}}/>)}
      </span></td>
     <td className="py-1.5 text-right tabular-nums text-foreground">{count(segment.leads)}</td>
     <td className="py-1.5 text-right tabular-nums text-foreground">{count(segment.booked)}</td>
     <td className="py-1.5 text-right tabular-nums text-muted-foreground">{percent(segment.booked,segment.leads)}</td>
     <td className="py-1.5 text-right tabular-nums text-foreground">
      {money(basis==='fee'?segment.bookedFee:segment.bookedValue)}</td>
     <td className="py-1.5 text-right tabular-nums text-foreground"
      title={segment.returnOnSpend==null
       ?'No advertising spend is attributed to these leads.'
       :`${money(segment.bookedFee)} of planning fee on ${money(segment.cost)} of spend`}>
      {segment.returnOnSpend==null
       ?<span className="text-muted-foreground">—</span>
       :<span className={segment.returnOnSpend<1?'text-destructive':undefined}>{multiple(segment.returnOnSpend)}</span>}</td>
    </tr>;
   })}</tbody>
  </table>
  </div>
  <p className="pt-1 text-[10px] text-muted-foreground">
   Return is the planning fee these leads booked per € of channel spend they carry; channels that
   cost nothing are left blank. Pick a row to narrow the funnel above to that {funnel.dimensionLabel.toLowerCase()}.
  </p>
 </div>;
}

registerWidget('plannerLeadFunnel',LeadFunnelWidget);
