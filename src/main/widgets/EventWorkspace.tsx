import {EntityListWidget,Segmented,Button,Badge,Select,SelectContent,SelectItem,SelectTrigger,SelectValue,registerWidget,useState,useEffect,useCallback,useUiEvents,useRef,React} from '@onno/widget-sdk';
type Row={id:string;[key:string]:any};
type Crew={id:string;contact:string;contactId:string|null;role:string;scope:string|null};
/** One column of the scenario grid: an estimate, as the comparison endpoint hands it over. */
type ScenarioColumn={id:string;label:string;scenario:string|null;revision:number|null;number:string|null;
 total:number|string;unknown:number;deposit:number|string|null;priceBasis:string|null;selected:boolean};
/** One quoted position, with `amounts`/`states` positional against the columns; null = not quoted. */
type ScenarioRow={category:string;article:string;details:string|null;phase:string;kind:string;
 amounts:(number|string|null)[];states:(string|null)[]};
type Scenarios={scenarios:ScenarioColumn[];rows:ScenarioRow[];commentsDiffer:boolean};
type EventHeader={id:string;name:string;code:string;client:string;clientId:string|null;stage:string;startDate:string|null;endDate:string|null;location:string|null;guests:number|null;currency:string;notes:string|null;crew:Crew[]};
type Commitment={invoiceId:string;number:string;counterparty:string;details:string|null;amount:number;paid:number;posted:boolean};
type Line={id:string|null;category:string;article:string;articleId:string|null;phase:string|null;phaseLabel:string;details:string|null;contractor:string|null;contractorId:string|null;state:string|null;stateLabel:string;kind:string|null;kindLabel:string;quantity:number|null;unitPrice:number|null;amount:number|null;committed:number;paid:number;budgeted:boolean;commitments:Commitment[]};
type Category={category:string;estimated:number;committed:number;paid:number;unpriced:number;lines:Line[]};
/**
 * The same lines, gathered under a different heading. The server groups by budget category, which
 * answers "what is this money for"; a planner chasing a quote needs "who is holding it", and one
 * reading the run sheet needs "which day is it". Regrouped here because it is the same payload
 * read three ways — asking the server again would be three endpoints that could disagree.
 */
function regroup(categories:Category[],mode:string):Category[]{
 if(mode==='category')return categories;
 const key=(line:Line)=>mode==='supplier'
   ? (line.contractor||'Nobody assigned yet')
   : (line.phaseLabel&&line.phaseLabel!=='—'?line.phaseLabel:'Unscheduled');
 const groups=new Map<string,Line[]>();
 for(const category of categories)
  for(const line of category.lines){
   const name=key(line);
   if(!groups.has(name))groups.set(name,[]);
   groups.get(name)!.push(line);
  }
 return [...groups.entries()].map(([name,lines])=>({
   category:name,
   estimated:lines.reduce((sum,l)=>sum+Number(l.amount??0),0),
   committed:lines.reduce((sum,l)=>sum+Number(l.committed??0),0),
   paid:lines.reduce((sum,l)=>sum+Number(l.paid??0),0),
   unpriced:lines.filter(l=>l.amount==null).length,
   lines,
 // Biggest first: on this tab the question is where the money went, and the answer is the top rows.
 })).sort((a,b)=>b.estimated-a.estimated);
}
type Commission={contractor:string;rate:number;forecastBase:number;forecast:number;bookedBase:number;booked:number;eventRate:boolean};
type Margin={forecastRevenue:number;forecastCost:number;forecastFee:number;forecastMarkup:number;forecastCommission:number;forecastTotal:number;forecastRate:number;forecastUnpriced:number;hasEstimate:boolean;bookedRevenue:number;bookedCost:number;bookedFee:number;bookedMarkup:number;bookedCommission:number;bookedTotal:number;bookedRate:number;fullyInvoiced:boolean;commissions:Commission[]};
type Workspace={event:EventHeader;categories:Category[];breakdownOf:string|null;selectedBudget:string|null;canWrite:boolean;canSeeMargin:boolean;budgets:Row[];invoices:Row[];payments:Row[];clientBilled:number;supplierBilled:number;received:number;paid:number};
const STAGES:Record<string,string>={PLANNING:'Planning',CONFIRMED:'Confirmed',COMPLETED:'Completed',CANCELLED:'Cancelled'};
const day=(value:string|null)=>value?new Intl.DateTimeFormat('en-GB',{day:'numeric',month:'short',year:'numeric'}).format(new Date(value)):null;
/** "3 – 5 Jun 2027", or the single day, or nothing when the dates are still open. */
const dates=(from:string|null,to:string|null)=>{const a=day(from),b=day(to);return a&&b&&a!==b?`${a} – ${b}`:a||b||null;};
/** Imported categories arrive shouting ("WEDDING"); title-case those, leave authored names alone. */
const pretty=(name:string)=>/^[A-Z0-9 _\-]+$/.test(name)
 ? name.toLowerCase().replace(/(^|[ _-])([a-z])/g,(_,sep,c)=>sep.replace('_',' ')+c.toUpperCase())
 : name;
// The symbol trails the number, the way a euro amount is written in the markets this business
// works in. Grouping and decimals stay as they were; only the side the € sits on changes.
const money=(n:unknown)=>`${new Intl.NumberFormat('en-GB',{minimumFractionDigits:2,maximumFractionDigits:2}).format(Number(n??0))} €`;
// The event page is itself a workspace, so a record it opens takes over the main area rather than
// stacking in the detail island beside it — hence the "main/" intent.
const openRecord=(kind:string,name:string,id:string)=>window.dispatchEvent(new CustomEvent('onno:action',{detail:`onno://main/${kind}/${name.replace(/([a-z0-9])([A-Z])/g,"$1_$2").toLowerCase()}/${id}`}));
async function request(path:string,body?:unknown){const token=document.cookie.split(';').map(s=>s.trim()).find(s=>s.startsWith('XSRF-TOKEN='))?.slice(11);const r=await fetch('/api/planner/events'+path,{credentials:'same-origin',...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},body:JSON.stringify(body)})});if(!r.ok){const e=await r.json().catch(()=>({}));throw new Error(e.message||e.detail||`Request failed (${r.status})`);}return r.json();}
/**
 * Pull a workbook down as a file. It goes through fetch rather than a plain link so a refused or
 * failed export reports itself in the page instead of opening a blank tab of JSON.
 */
async function download(path:string,fallback:string){
 const r=await fetch('/api/planner/events'+path,{credentials:'same-origin'});
 if(!r.ok){const e=await r.json().catch(()=>({}));throw new Error(e.message||e.detail||`Export failed (${r.status})`);}
 const disposition=r.headers.get('content-disposition')||'';
 const encoded=/filename\*=UTF-8''([^;]+)/i.exec(disposition);
 const plain=/filename="([^"]+)"/i.exec(disposition);
 const name=encoded?decodeURIComponent(encoded[1]):plain?plain[1]:fallback;
 const url=URL.createObjectURL(await r.blob());
 const link=document.createElement('a');
 link.href=url;link.download=name;document.body.appendChild(link);link.click();link.remove();
 setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function findList(value:any):any {
 if(!value||typeof value!=='object')return null;
 if(value.list?.kind==='documents')return value.list;
 for(const child of Object.values(value)){const found=findList(child);if(found)return found;}
 return null;
}
function NativeDocuments({name,event}:{name:string;event:string}){
 const [list,setList]=useState<any>(null);const [error,setError]=useState('');
 useEffect(()=>{let active=true;setList(null);setError('');
  fetch(`/api/divkit/documents/${name}`,{credentials:'same-origin'}).then(async r=>{if(!r.ok)throw new Error('Unable to load documents');const descriptor=findList(await r.json());if(!descriptor)throw new Error('Document list unavailable');if(active)setList({...descriptor,embedded:true,baseFilter:`event = '${event}'`,newUrl:descriptor.newUrl?`${descriptor.newUrl}?event=${event}`:null,columns:descriptor.columns.filter((c:any)=>c.fieldName!=='event')});}).catch(e=>{if(active)setError(e.message);});
  return()=>{active=false;};
 },[name,event]);
 return error?<p role="alert">{error}</p>:list?<EntityListWidget list={list}/>:<p>Loading…</p>;
}

/**
 * Any lucide glyph by name, drawn by the shell's onno-icon bridge — app widgets bundle without
 * lucide. The element takes name/size as DOM properties, so they are assigned through a ref.
 */
function Icon({name,size=14,className=''}:{name:string;size?:number;className?:string}){
 const ref=useRef<HTMLElement|null>(null);
 useEffect(()=>{const el=ref.current as (HTMLElement&{name?:string;size?:number})|null;if(!el)return;el.name=name;el.size=size;},[name,size]);
 return React.createElement('onno-icon',{ref,'aria-hidden':'true',className,style:{display:'inline-flex',width:size,height:size,flexShrink:0}});
}

/** The header an embedded list carries — title, row count, actions — so authored sections match. */
function SectionHeader({title,count,children}:{title:string;count?:number;children?:any}){
 return <div className="flex flex-wrap items-center justify-between gap-2">
  <h2 className="flex items-center gap-2 text-sm font-medium">
   {title}
   {count!=null&&<span className="rounded-pill bg-muted px-2 py-0.5 text-xs font-normal text-muted-foreground">
    {count} {count===1?'row':'rows'}
   </span>}
  </h2>
  {children&&<div className="flex flex-wrap items-center gap-2">{children}</div>}
 </div>;
}

/** One fact about the event: a glyph and its value, for the line under the title. */
function Fact({icon,children}:{icon:string;children:any}){
 return <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
  <Icon name={icon} size={14}/>{children}
 </span>;
}

/**
 * One money figure: the number people came for, with the two figures that qualify it underneath.
 * `tone` tints the headline where a number carries a verdict — money still owed, money still due.
 */
function Stat({label,value,tone,lines,badge,progress}:{label:string;value:string;tone?:'debt'|'good';lines?:(string|null)[];badge?:any;progress?:number}){
 // A share of the estimate that is already committed. Over 100% is the number that matters most,
 // so it turns red and the bar stays full rather than running off the card.
 const over=progress!=null&&progress>1;
 const width=progress==null?0:Math.max(0,Math.min(1,progress))*100;
 return <div className="min-w-0 rounded-panel border border-border bg-card px-4 py-3">
  <div className="flex items-center justify-between gap-2">
   <p className="truncate text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
   {badge}
  </div>
  <p className={`mt-1 truncate text-lg font-semibold tabular-nums ${tone==='debt'?'text-amber-500':tone==='good'?'text-emerald-500':''}`}>{value}</p>
  {progress!=null&&<div className="mt-2 h-1.5 overflow-hidden rounded-pill bg-muted" role="progressbar"
    aria-valuenow={Math.round(width)} aria-valuemin={0} aria-valuemax={100}>
   <div className={`h-full rounded-pill ${over?'bg-destructive':'bg-primary'}`} style={{width:`${width}%`}}/>
  </div>}
  {lines?.filter(Boolean).map((line,i)=><p key={i} className="mt-0.5 truncate text-xs text-muted-foreground tabular-nums">{line}</p>)}
 </div>;
}

/** A money figure in a detail table; nothing at all rather than a confident "€0.00". */
function Amount({value,muted,fallback}:{value:number|null|undefined;muted?:boolean;fallback?:string}){
 if(value==null||Number(value)===0)return <span className="text-xs text-muted-foreground">{fallback||'—'}</span>;
 return <span className={`tabular-nums ${muted?'text-muted-foreground':''}`}>{money(value)}</span>;
}

/**
 * One article of the estimate: what it covers, who is on it, and every supplier invoice raised
 * against it. The comment text is what the venue actually wrote, so it is kept verbatim and
 * clamped rather than truncated — the detail is the point of this table.
 */
function LineRow({line}:{line:Line}){
 const [open,setOpen]=useState(false);
 const details=line.details||'';
 const long=details.length>150||details.split('\n').length>2;
 const unit=line.unitPrice!=null&&line.quantity!=null&&Number(line.quantity)!==1
  ? `${Number(line.quantity)} × ${money(line.unitPrice)}` : null;
 return <>
  <tr className={`border-t border-border align-top ${line.budgeted?'':'bg-amber-500/5'}`}>
   <td className="px-3 py-2">
    <p className="font-medium">{line.article}</p>
    {details&&<p className={`mt-0.5 whitespace-pre-line text-xs leading-relaxed text-muted-foreground ${open?'':'line-clamp-2'}`}>{details}</p>}
    {long&&<button type="button" className="mt-1 text-xs text-primary underline-offset-2 hover:underline"
      aria-expanded={open} onClick={()=>setOpen(v=>!v)}>{open?'Show less':'Show more'}</button>}
   </td>
   <td className="px-3 py-2">{line.contractor||<span className="text-xs text-muted-foreground">Not assigned</span>}</td>
   <td className="px-3 py-2 text-xs text-muted-foreground">{line.phaseLabel}</td>
   <td className="px-3 py-2">
    <span className="flex flex-wrap items-center gap-1">
     <Badge variant={line.budgeted?'secondary':'outline'}>{line.stateLabel}</Badge>
     {/* A fee or a held deposit is not a supplier cost; only those two are worth calling out. */}
     {line.kind&&line.kind!=='SERVICE'&&<Badge variant="outline">{line.kindLabel}</Badge>}
    </span>
    {unit&&<p className="mt-1 text-xs text-muted-foreground tabular-nums">{unit}</p>}
   </td>
   <td className="px-3 py-2 text-right"><Amount value={line.amount} fallback={line.budgeted?'Unpriced':'—'}/></td>
   <td className="px-3 py-2 text-right"><Amount value={line.committed}/></td>
   <td className="px-3 py-2 text-right"><Amount value={line.paid} muted/></td>
  </tr>
  {/* The invoices behind the committed figure, so "€40,000 committed" can be opened, not just read. */}
  {line.commitments.map(c=><tr key={c.invoiceId} className="border-t border-border/50 bg-muted/30">
   <td className="px-3 py-1.5 pl-8 text-xs text-muted-foreground" colSpan={4}>
    <button type="button" className="text-primary underline-offset-2 hover:underline"
      onClick={()=>openRecord('documents','EventInvoices',c.invoiceId)}>{c.number}</button>
    {' · '}{c.counterparty}{c.details?` · ${c.details}`:''}
   </td>
   <td/>
   <td className="px-3 py-1.5 text-right text-xs text-muted-foreground tabular-nums">{money(c.amount)}</td>
   <td className="px-3 py-1.5 text-right text-xs text-muted-foreground tabular-nums">
    {Number(c.paid)===0?'Unpaid':money(c.paid)}
   </td>
  </tr>)}
 </>;
}

/**
 * A budget category that opens. The closed row carries the four numbers that matter; opening it
 * shows the articles those numbers are made of — the question a percentage cannot answer.
 */
function CategoryPanel({category,open,onToggle}:{category:Category;open:boolean;onToggle:()=>void}){
 const left=Number(category.estimated)-Number(category.committed);
 const over=Number(category.estimated)>0&&left<0;
 return <div className="overflow-hidden rounded-panel border border-border">
  <button type="button" onClick={onToggle} aria-expanded={open}
    className="flex w-full items-center gap-3 bg-card px-3 py-2.5 text-left transition-colors hover:bg-muted/50">
   <Icon name={open?'chevron-down':'chevron-right'} size={16} className="text-muted-foreground"/>
   <span className="min-w-0 flex-1">
    <span className="block truncate text-sm font-medium">{pretty(category.category)}</span>
    <span className="block text-xs text-muted-foreground">
     {category.lines.length} {category.lines.length===1?'article':'articles'}
     {category.unpriced>0&&` · ${category.unpriced} unpriced`}
    </span>
   </span>
   {/* No `sm:` here on purpose. The widget stylesheet is linked before the shell's, so the host's
       plain `.hidden` wins against a `sm:flex` at equal specificity — these four figures were
       invisible at every width, which left a row of headings nobody could read a number off.
       Wrapping is what makes them behave on a narrow pane instead. */}
   <span className="flex shrink-0 flex-wrap justify-end gap-x-6 gap-y-1 text-right">
    <span className="w-28 text-sm tabular-nums">{money(category.estimated)}</span>
    <span className="w-28 text-sm tabular-nums">{money(category.committed)}</span>
    <span className="w-28 text-sm tabular-nums text-muted-foreground">{money(category.paid)}</span>
    <span className={`w-28 text-sm tabular-nums ${over?'text-destructive':''}`}
      title={over?'Committed past the estimate':'Still to commit'}>
     {over?'−':''}{money(Math.abs(left))}
    </span>
   </span>
  </button>
  {open&&<div className="max-h-[60vh] overflow-auto border-t border-border">
   <table className="w-full min-w-[52rem] text-sm">
    {/* Sticky within this scroller: with thirty-six articles open the column names are otherwise
        gone by the second screenful, and "Committed" and "Paid" are two identical columns of euros. */}
    <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
     <tr>
      <th className="px-3 py-2 text-left font-medium">Article and what it covers</th>
      <th className="px-3 py-2 text-left font-medium">Contractor</th>
      <th className="px-3 py-2 text-left font-medium">Event day</th>
      <th className="px-3 py-2 text-left font-medium">Status</th>
      <th className="px-3 py-2 text-right font-medium">Estimated</th>
      <th className="px-3 py-2 text-right font-medium">Committed</th>
      <th className="px-3 py-2 text-right font-medium">Paid</th>
     </tr>
    </thead>
    <tbody>{category.lines.map((line,i)=><LineRow key={line.id||`extra-${i}`} line={line}/>)}</tbody>
   </table>
  </div>}
 </div>;
}


/**
 * The estimates side by side — the layout the venue quotes themselves arrive in, and the one the
 * scenario export writes. A position quoted by two venues sits on one row with a column each, so
 * "Villa Balbiano is €40k more" is read across rather than reconstructed from two open documents.
 *
 * <p>Choosing is the last act, not the first: every column carries its own total and its own
 * <b>Use this estimate</b>, so the comparison is what leads to the decision. The chosen column is
 * the one the totals above and the expense breakdown below are computed from.</p>
 *
 * <p>Categories are folded by default. An imported quote runs to dozens of positions, and the
 * category subtotals are what a first pass compares; the positions under them are for the argument
 * about a particular florist.</p>
 */
function ScenarioComparison({id,reading,onRead,canWrite,busy,onUse,onCopy}:{
 id:string;reading:string;onRead:(budget:string)=>void;canWrite:boolean;busy:boolean;
 onUse:(budget:string)=>void;onCopy:(budget:string)=>void}){
 const [data,setData]=useState<Scenarios|null>(null);
 const [error,setError]=useState('');
 const [open,setOpen]=useState<Set<string>>(new Set());
 // Comparing is subtracting. With two columns of forty positions the eye cannot do it, so the page
 // does: every column is read against the one driving the event, and the rows where the scenarios
 // agree can be put away entirely — what is left on screen is then exactly the decision.
 const [onlyDiffs,setOnlyDiffs]=useState(false);
 const load=useCallback(async()=>{try{setData(await request(`/${id}/scenarios`));setError('');}
  catch(e){setError((e as Error).message);}},[id]);
 useEffect(()=>{void load();},[load]);
 useUiEvents(()=>{void load();},{types:['created','updated','deleted','posted','unposted']});

 if(error)return <p role="alert" className="rounded-field border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">{error}</p>;
 if(!data)return <p className="text-sm text-muted-foreground">Laying the estimates out…</p>;
 const columns=data.scenarios;
 if(!columns.length)return null;

 // The column everything is measured against: the estimate this event runs on, or the first one
 // when nothing is chosen yet. A delta against an arbitrary column would be a number nobody asked for.
 const baseline=Math.max(0,columns.findIndex(column=>column.selected));
 /** A position the scenarios disagree on — including one that only some of them quote at all. */
 const differs=(values:(number|null)[])=>{
  if(columns.length<2)return false;
  if(values.some(value=>value==null)&&values.some(value=>value!=null))return true;
  const quoted=values.filter((value):value is number=>value!=null);
  return quoted.some(value=>value!==quoted[0]);
 };
 const visible=onlyDiffs
  ? data.rows.filter(row=>differs(row.amounts.map(a=>a==null?null:Number(a))))
  : data.rows;

 // Grouped here rather than server-side: the endpoint returns the export's own row order, and the
 // page only needs to fold it — regrouping in Java would be a second ordering to keep in step.
 const groups:{category:string;rows:ScenarioRow[];subtotals:number[]}[]=[];
 for(const row of visible){
  let group=groups.find(entry=>entry.category===row.category);
  if(!group){group={category:row.category,rows:[],subtotals:columns.map(()=>0)};groups.push(group);}
  group.rows.push(row);
  row.amounts.forEach((amount,index)=>{if(amount!=null)group!.subtotals[index]+=Number(amount);});
 }
 const totals=columns.map(column=>Number(column.total??0));
 /** The cheapest column on a row, for the marker — only where there is a real choice to mark. */
 const cheapest=(values:(number|null)[])=>{
  const quoted=values.filter((value):value is number=>value!=null);
  if(quoted.length<2)return null;
  const low=Math.min(...quoted);
  return quoted.some(value=>value!==low)?low:null;
 };
 /** The gap to the driving estimate, printed under the amount. Nothing on the baseline itself. */
 const Delta=({value,base,self}:{value:number|null;base:number|null;self:boolean})=>{
  if(self||value==null||base==null)return null;
  const gap=value-base;
  if(Math.round(gap)===0)return <span className="block text-[11px] text-muted-foreground">same</span>;
  return <span className={`block text-[11px] tabular-nums ${gap<0?'text-emerald-500':'text-amber-500'}`}
    title={`${gap<0?'Cheaper':'Dearer'} than the estimate driving this event`}>
   {gap<0?'−':'+'}{money(Math.abs(gap))}</span>;
 };
 const Amount=({value,low,state}:{value:number|null;low:number|null;state?:string|null})=>value==null
  ? <span className="text-muted-foreground/60" title="Not quoted in this estimate">—</span>
  : <span className={value===low?'font-medium text-emerald-500':''}
     title={[value===low?'Lowest quote on this line':null,state&&state!=='—'?`Price: ${state}`:null]
      .filter(Boolean).join(' · ')||undefined}>{money(value)}</span>;

 const differing=data.rows.filter(row=>differs(row.amounts.map(a=>a==null?null:Number(a)))).length;
 return <div className="space-y-2">
  {columns.length>1&&<div className="flex flex-wrap items-center justify-between gap-2">
   <p className="text-xs text-muted-foreground">
    Every column is read against <b className="font-medium text-foreground">
     {columns[baseline]?.scenario||'the first estimate'}</b>, the one driving this event.
    {differing
      ? ` ${differing} of ${data.rows.length} positions are quoted differently.`
      : ' The scenarios agree on every position.'}
   </p>
   {differing>0&&<Button size="toolbar" variant={onlyDiffs?'secondary':'ghost'} aria-pressed={onlyDiffs}
     title="Hide the positions the estimates agree on"
     onClick={()=>setOnlyDiffs(value=>!value)}>
    <Icon name="git-compare-arrows" size={16}/>Only differences
   </Button>}
  </div>}
  {/* A height and a scroller of its own, so the scenario columns stay put while fifty-three
      positions go past. Without the bound, `sticky` has nothing to stick inside. */}
  <div className="max-h-[70vh] overflow-auto rounded-panel border border-border">
   <table className="w-full border-collapse text-sm">
    <thead className="sticky top-0 z-30 bg-card">
     <tr className="border-b border-border">
      {/* A percentage, not a min-width alone: with two quotes on file the estimate columns should
          split the slack between them rather than leave it all in the position column. */}
      <th className="sticky left-0 z-10 w-[38%] min-w-[15rem] bg-card px-3 py-2 text-left align-bottom text-xs font-medium text-muted-foreground">
       Position
       <span className="block pt-0.5 font-normal normal-case">
        {columns.length===1?'One estimate on file':`${columns.length} estimates, side by side`}
       </span>
      </th>
      {columns.map(column=>{
       const active=column.id===reading;
       return <th key={column.id} scope="col"
         className={`min-w-[11rem] border-l border-border px-3 py-2 text-left align-top ${active?'bg-primary/5':''}`}>
        {/* Reading a column and pointing the event at it are different acts: the header selects the
            column the breakdown below follows, the button inside it changes what the event runs on. */}
        <button type="button" onClick={()=>onRead(column.id)} aria-pressed={active}
          className="block w-full text-left"
          title="Read this estimate's expenses in the breakdown below">
         <span className="block truncate text-sm font-medium">{column.scenario||'Estimate'}</span>
         <span className="block truncate text-[11px] text-muted-foreground">
          {[column.number,column.revision&&column.revision>1?`v${column.revision}`:null].filter(Boolean).join(' · ')||'—'}
         </span>
         <span className="block truncate pt-1 text-base font-semibold tabular-nums">{money(column.total)}</span>
         <span className="block truncate text-[11px] font-normal text-muted-foreground">
          {column.unknown>0?`${column.unknown} ${column.unknown===1?'article':'articles'} still unpriced`
            :column.priceBasis||'\u00a0'}
         </span>
        </button>
        <div className="flex items-center gap-1 pt-1.5">
         {column.selected
          ? <span className="inline-flex h-7 flex-1 items-center justify-center gap-1.5 rounded-field bg-primary/10 px-2 text-[11px] font-medium text-primary">
             <Icon name="check" size={13}/>Driving the totals</span>
          : <Button variant="subtle" className="h-7 flex-1 text-[11px]" disabled={!canWrite}
              title="Drive this event's totals from this estimate"
              onClick={()=>onUse(column.id)}>Use this estimate</Button>}
         <Button size="toolbar" variant="ghost" title="Open the estimate"
           onClick={()=>openRecord('documents','EventBudgets',column.id)}><Icon name="pencil" size={13}/></Button>
         <Button size="toolbar" variant="ghost" disabled={!canWrite||busy}
           title="Copy as the next version of this scenario"
           onClick={()=>onCopy(column.id)}><Icon name="copy" size={13}/></Button>
        </div>
       </th>;
      })}
     </tr>
    </thead>
    <tbody>{groups.map(group=>{
     const expanded=open.has(group.category);
     const lowSubtotal=cheapest(group.subtotals.map((value,index)=>
      group.rows.some(row=>row.amounts[index]!=null)?value:null));
     return <React.Fragment key={group.category}>
      <tr className="border-b border-border/60 bg-muted/30">
       <th scope="row" className="sticky left-0 z-10 bg-muted/30 px-3 py-1.5 text-left font-medium">
        <button type="button" className="flex items-center gap-1.5 text-left"
          onClick={()=>setOpen(current=>{const next=new Set(current);
           if(next.has(group.category))next.delete(group.category);else next.add(group.category);return next;})}
          aria-expanded={expanded}>
         <Icon name={expanded?'chevron-down':'chevron-right'} size={14} className="text-muted-foreground"/>
         {pretty(group.category)}
         <span className="text-[11px] font-normal text-muted-foreground">
          {group.rows.length} {group.rows.length===1?'position':'positions'}</span>
        </button>
       </th>
       {columns.map((column,index)=>
        <td key={column.id} className={`border-l border-border px-3 py-1.5 text-right tabular-nums ${column.id===reading?'bg-primary/5':''}`}>
         <Amount value={group.rows.some(row=>row.amounts[index]!=null)?group.subtotals[index]:null} low={lowSubtotal}/>
         <Delta value={group.rows.some(row=>row.amounts[index]!=null)?group.subtotals[index]:null}
           base={group.rows.some(row=>row.amounts[baseline]!=null)?group.subtotals[baseline]:null}
           self={index===baseline}/>
        </td>)}
      </tr>
      {expanded&&group.rows.map((row,rowIndex)=>{
       const values=row.amounts.map(amount=>amount==null?null:Number(amount));
       const low=cheapest(values);
       return <tr key={`${group.category}-${rowIndex}`} className="border-b border-border/40">
        <td className="sticky left-0 z-10 bg-card px-3 py-1.5 pl-8">
         <span className="block truncate">{row.article}</span>
         <span className="block truncate text-[11px] text-muted-foreground">
          {[row.phase,row.details].filter(value=>value&&value!=='—').join(' · ')||'\u00a0'}</span>
        </td>
        {values.map((value,index)=>
         <td key={columns[index].id} className={`border-l border-border px-3 py-1.5 text-right tabular-nums ${columns[index].id===reading?'bg-primary/5':''}`}>
          <Amount value={value} low={low} state={row.states[index]}/>
          <Delta value={value} base={values[baseline]} self={index===baseline}/>
         </td>)}
       </tr>;
      })}
     </React.Fragment>;
    })}</tbody>
    <tfoot>
     <tr className="border-t border-border bg-muted/50">
      <th scope="row" className="sticky left-0 z-10 bg-muted/50 px-3 py-2 text-left font-medium">Total</th>
      {totals.map((total,index)=>
       <td key={columns[index].id} className={`border-l border-border px-3 py-2 text-right text-base font-semibold tabular-nums ${columns[index].id===reading?'bg-primary/5':''}`}>
        <Amount value={total} low={cheapest(totals)}/>
        <Delta value={total} base={totals[baseline]} self={index===baseline}/>
       </td>)}
     </tr>
    </tfoot>
   </table>
  </div>
  <p className="text-[11px] text-muted-foreground">
   Green is the cheapest quote on a line — cheapest is not automatically the one to take, it is the
   one to ask about. {data.commentsDiffer?'Venues wrote different comments on some positions; the export carries each in full. ':''}
   Open a category to compare position by position.
  </p>
 </div>;
}

/**
 * What the event earns Wedding Planner — the one panel on this page that is never turned towards a client.
 *
 * It sits behind its own tab rather than among the four headline figures for exactly that reason:
 * the estimate, the client balance and the cash position are all shown to couples in meetings, and
 * this is not. It is also served by its own endpoint, so narrowing `planner.events.margin-roles` takes
 * it away from a role rather than merely hiding it.
 */
function MarginPanel({id}:{id:string}){
 const [margin,setMargin]=useState<Margin|null>(null);
 const [error,setError]=useState('');
 const load=useCallback(async()=>{try{setMargin(await request(`/${id}/margin`));setError('');}catch(e){setError((e as Error).message);}},[id]);
 useEffect(()=>{void load();},[load]);
 useUiEvents(()=>{void load();},{types:['created','updated','deleted','posted','unposted']});
 if(error)return <p role="alert" className="rounded-field border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">{error}</p>;
 if(!margin)return <p className="text-sm text-muted-foreground">Working out the margin…</p>;

 // Three rows, because Wedding Planner earns in three unrelated ways and a single blended number hides which
 // of them is actually carrying the event. The colour is the same one the composition bar paints
 // the share in, so a slice can be read back to the row it came from.
 const sources=[
  {label:'Agency fee',color:'hsl(var(--chart-1))',forecast:margin.forecastFee,booked:margin.bookedFee,
   note:'Charged in the open, on the estimate and the client invoice.'},
  {label:'Markup on supplier articles',color:'hsl(var(--chart-2))',forecast:margin.forecastMarkup,booked:margin.bookedMarkup,
   note:'What the couple is quoted, less what the supplier charges us.'},
  {label:'Contractor commissions',color:'hsl(var(--chart-3))',forecast:margin.forecastCommission,booked:margin.bookedCommission,
   note:'Rebated by suppliers out of their own invoices. On no client document.'},
 ];
 // The bar is drawn only when every source is a positive share of a positive total. A negative
 // markup — an article quoted under what it costs us — would otherwise draw backwards, and a bar
 // that lies about the shape of the margin is worse than no bar.
 const total=Number(margin.forecastTotal);
 const shares=total>0&&sources.every(s=>Number(s.forecast)>=0)
  ? sources.map(s=>({label:s.label,color:s.color,width:Number(s.forecast)/total*100}))
  : null;

 return <div className="space-y-4">
  {/* Two figures, side by side, in the same island the four event headlines use — this tab is part
      of the page, not a panel with a livery of its own. The warning that it is internal rides on a
      badge rather than tinting the whole surface: status colour belongs to a marker. */}
  <div className="grid gap-3" style={{gridTemplateColumns:'repeat(auto-fit,minmax(300px,1fr))'}}>
   <div className="min-w-0 rounded-panel border border-border bg-card px-4 py-3">
    <div className="flex items-center justify-between gap-2">
     <p className="truncate text-xs uppercase tracking-wide text-muted-foreground">Forecast margin</p>
     <Badge variant="outline" className="gap-1 text-[hsl(var(--warning))]">
      <Icon name="lock" size={14}/>Internal
     </Badge>
    </div>
    <p className="mt-1 truncate text-2xl font-semibold tabular-nums tracking-tight">{money(margin.forecastTotal)}</p>
    {shares&&<div className="mt-2 flex h-1.5 gap-0.5 overflow-hidden rounded-pill bg-muted"
      role="img" aria-label={shares.map(s=>`${s.label} ${Math.round(s.width)}%`).join(', ')}>
     {shares.map(s=><span key={s.label} style={{width:`${s.width}%`,backgroundColor:s.color}}/>)}
    </div>}
    <p className="mt-1.5 text-xs text-muted-foreground tabular-nums">
     {margin.hasEstimate
       ? `${margin.forecastRate}% of ${money(margin.forecastRevenue)} quoted · ${money(margin.forecastCost)} goes out to suppliers`
       : 'Choose an estimate to forecast what this event earns.'}
    </p>
    {margin.forecastUnpriced>0&&<p className="mt-1 flex items-start gap-1.5 text-xs text-[hsl(var(--warning))]">
     <Icon name="triangle-alert" size={14}/>
     <span>{margin.forecastUnpriced===1?'1 article is':`${margin.forecastUnpriced} articles are`} still
      unpriced, so the forecast is partial.</span>
    </p>}
   </div>

   {/* Booked margin is a trap for most of an event's life: bill the couple their deposit before any
       supplier invoice arrives and the event reads as pure profit. The tile says what it counts,
       beside the number, rather than leaving a paragraph to catch the misreading later. */}
   <div className="min-w-0 rounded-panel border border-border bg-card px-4 py-3">
    <div className="flex items-center justify-between gap-2">
     <p className="truncate text-xs uppercase tracking-wide text-muted-foreground">Booked so far</p>
     <Badge variant={margin.fullyInvoiced?'secondary':'outline'}>
      {margin.fullyInvoiced?'Fully invoiced':'Partly invoiced'}
     </Badge>
    </div>
    <p className="mt-1 truncate text-2xl font-semibold tabular-nums tracking-tight text-muted-foreground">
     {money(margin.bookedTotal)}
    </p>
    <p className="mt-2 text-xs text-muted-foreground tabular-nums">
     {margin.fullyInvoiced
       ? `${margin.bookedRate}% of ${money(margin.bookedRevenue)} invoiced · ${money(margin.bookedCost)} from suppliers`
       : `${money(margin.bookedRevenue)} billed to the couple · ${money(margin.bookedCost)} from suppliers`}
    </p>
    <p className="mt-1 text-xs text-muted-foreground">
     {margin.fullyInvoiced
       ? 'Both sides are invoiced in full, so this is what the event actually earned.'
       : 'Posted invoices only. It swings with whoever billed first until both sides are invoiced — plan against the forecast.'}
    </p>
   </div>
  </div>

  <div className="space-y-2">
   <SectionHeader title="Where the money comes from"/>
   {/* The table, not the page, is the horizontal scroll boundary: a money column squeezed narrow
       wraps mid-figure ("15,44 / 6.50 €"), which is unreadable in a way a scrollbar never is. */}
   <div className="overflow-x-auto rounded-field border border-border">
    <table className="w-full min-w-[520px] text-sm">
     <thead className="bg-muted/60 text-xs text-muted-foreground">
      <tr>
       <th className="px-3 py-2 text-left font-medium">Source</th>
       <th className="px-3 py-2 text-right font-medium">Forecast</th>
       <th className="px-3 py-2 text-right font-medium">Booked</th>
      </tr>
     </thead>
     <tbody>
      {sources.map(s=><tr key={s.label} className="border-t border-border align-top">
       <td className="px-3 py-2">
        <p className="flex items-center gap-2">
         <span className="size-2.5 shrink-0 rounded-pill" style={{backgroundColor:s.color}}/>
         {s.label}
        </p>
        <p className="mt-0.5 pl-[18px] text-xs text-muted-foreground">{s.note}</p>
       </td>
       <td className="whitespace-nowrap px-3 py-2 text-right"><Amount value={s.forecast}/></td>
       <td className="whitespace-nowrap px-3 py-2 text-right"><Amount value={s.booked} muted/></td>
      </tr>)}
      <tr className="border-t border-border bg-muted/40 font-medium">
       <td className="px-3 py-2">Total Wedding Planner earns</td>
       <td className="whitespace-nowrap px-3 py-2 text-right"><Amount value={margin.forecastTotal}/></td>
       <td className="whitespace-nowrap px-3 py-2 text-right"><Amount value={margin.bookedTotal}/></td>
      </tr>
     </tbody>
    </table>
   </div>
  </div>

  <div className="space-y-2">
   <SectionHeader title="Commission by supplier" count={margin.commissions.length}/>
   {/* Four columns, not six: the base a commission is charged on belongs under the figure it
       produced, where it reads as "20,085.54 € on 167,379.50 € quoted" rather than as two numbers
       the eye has to pair up across the table. */}
   {margin.commissions.length?<div className="overflow-x-auto rounded-field border border-border">
    <table className="w-full min-w-[560px] text-sm">
     <thead className="bg-muted/60 text-xs text-muted-foreground">
      <tr>
       <th className="px-3 py-2 text-left font-medium">Supplier</th>
       <th className="px-3 py-2 text-right font-medium">Rate</th>
       <th className="px-3 py-2 text-right font-medium">Forecast</th>
       <th className="px-3 py-2 text-right font-medium">Booked</th>
      </tr>
     </thead>
     <tbody>
      {margin.commissions.map(c=><tr key={c.contractor} className="border-t border-border align-top">
       <td className="truncate px-3 py-2">{c.contractor}</td>
       <td className="whitespace-nowrap px-3 py-2 text-right align-top">
        <span className="tabular-nums">{c.rate}%</span>
        {c.eventRate&&<p className="text-xs text-muted-foreground">this event</p>}
       </td>
       <td className="whitespace-nowrap px-3 py-2 text-right">
        <Amount value={c.forecast}/>
        <p className="text-xs text-muted-foreground tabular-nums">on {money(c.forecastBase)} quoted</p>
       </td>
       <td className="whitespace-nowrap px-3 py-2 text-right">
        <Amount value={c.booked} muted fallback="Not yet"/>
        {Number(c.bookedBase)>0&&<p className="text-xs text-muted-foreground tabular-nums">
         on {money(c.bookedBase)} posted
        </p>}
       </td>
      </tr>)}
     </tbody>
    </table>
   </div>:<p className="rounded-panel border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
    No supplier on this event rebates a commission yet. Set a rate on a contact card, or on the
    participant row when this event agreed its own, and assign the supplier to estimate articles.
   </p>}
  </div>
 </div>;
}

function EventWorkspace({id}:{id:string|undefined}){
 const [data,setData]=useState<Workspace|null>(null);
 const [selected,setSelected]=useState('');
 const [lineId,setLineId]=useState('');
 const [openCategories,setOpenCategories]=useState<Set<string>>(()=>new Set());
 const [tab,setTab]=useState('Estimates');
 const [grouping,setGrouping]=useState('category');
 const [error,setError]=useState('');
 const [busy,setBusy]=useState(false);
 // The breakdown is computed for one scenario, so the scenario being read travels with the request.
 // A ref, not state: picking a card must re-fetch once, not re-fetch and then re-render into a loop.
 const viewing=useRef('');
 const load=useCallback(async()=>{if(!id)return;try{
  let d=await request(`/${id}/workspace${viewing.current?`?budget=${viewing.current}`:''}`);
  // First visit: nothing is being read yet, so the server broke nothing down. Settle on the scenario
  // this event runs on — or its first — and ask again, rather than showing an empty table until the
  // user happens to click a card.
  const pick=d.budgets.some((b:Row)=>b.id===viewing.current)
    ? viewing.current : (d.breakdownOf||d.selectedBudget||d.budgets[0]?.id||'');
  if(pick&&pick!==d.breakdownOf)d=await request(`/${id}/workspace?budget=${pick}`);
  viewing.current=pick;setData(d);setSelected(pick);setError('');
 }catch(e){setError((e as Error).message);}},[id]);
 useEffect(()=>{viewing.current='';setSelected('');void load();},[load]);
 useUiEvents(()=>{void load();},{types:['created','updated','deleted','posted','unposted']});
 /** Read another scenario: the cards, the commit picker and the breakdown all follow this one id. */
 const choose=(budgetId:string)=>{if(budgetId===viewing.current)return;viewing.current=budgetId;setSelected(budgetId);setLineId('');void load();};
 const exporting=async(path:string,fallback:string)=>{setBusy(true);setError('');try{await download(path,fallback);}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 const run=async(action:()=>Promise<void>)=>{setBusy(true);setError('');try{await action();await load();}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 if(!id)return <p className="p-6 text-sm text-muted-foreground">Open an event to see its workspace.</p>;
 if(!data)return <p className="p-6 text-sm text-muted-foreground" role={error?'alert':undefined}>{error||'Loading event…'}</p>;

 const e=data.event;
 const when=dates(e.startDate,e.endDate);
 const b=data.budgets.find(b=>b.id===selected);
 const can=data.canWrite&&!busy;
 // An article is ready to become a supplier expense once someone owns it at an agreed price.
 const eligible=(b?.items||[]).filter((l:Row)=>l.contractorId&&l.state==='QUOTED'&&l.amount>0);
 const grouped=regroup(data.categories,grouping);
 // Unpriced articles sit in no total on this page, which is worth saying out loud rather than
 // leaving someone to wonder why the parts do not add up to the estimate.
 const unpriced=data.categories.reduce((sum,c)=>sum+Number(c.unpriced??0),0);
 const totals=data.categories.reduce((sum,c)=>({estimated:sum.estimated+Number(c.estimated),
   committed:sum.committed+Number(c.committed),paid:sum.paid+Number(c.paid)}),{estimated:0,committed:0,paid:0});
 const clientDue=Number(data.clientBilled)-Number(data.received);
 const supplierDue=Number(data.supplierBilled)-Number(data.paid);
 const cash=Number(data.received)-Number(data.paid);

 return <div className="space-y-5 p-1">
  {error&&<p role="alert" className="rounded-field border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">{error}</p>}

  <header className="flex flex-wrap items-start justify-between gap-3">
   <div className="min-w-0 space-y-2">
    <div className="flex flex-wrap items-center gap-2">
     <h1 className="truncate text-2xl font-semibold tracking-tight">{e.name||e.code}</h1>
     <Badge variant="secondary">{STAGES[e.stage]||e.stage}</Badge>
     <span className="text-xs text-muted-foreground">{e.code}</span>
    </div>
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
     <Fact icon="heart">{e.client}</Fact>
     <Fact icon="calendar-days">{when||'Dates to confirm'}</Fact>
     {e.location&&<Fact icon="map-pin">{e.location}</Fact>}
     {e.guests!=null&&<Fact icon="users">{e.guests} guests</Fact>}
    </div>
   </div>
   <div className="flex flex-wrap items-center gap-2">
    {b&&<Button size="toolbar" variant="subtle" onClick={()=>openRecord('documents','EventBudgets',b.id)}>
     <Icon name="file-text" size={16}/>Open estimate
    </Button>}
    {/* Straight into this event's folder in the Conversations inbox — the couple, the florist and
        the venue in one place, not just the client's own card. */}
    <Button size="toolbar" variant="subtle"
      onClick={()=>window.dispatchEvent(new CustomEvent('onno:action',{detail:`onno://main/conversations?mode=projects&folder=event_${e.id.replace(/-/g,'')}`}))}>
     <Icon name="messages-square" size={16}/>Event chat
    </Button>
    <Button size="toolbar" variant="subtle" onClick={()=>openRecord('catalogs','EventProjects',e.id)}>
     <Icon name="pencil" size={16}/>Edit event
    </Button>
   </div>
  </header>

  {/* The four numbers an event is judged by: what it is estimated at, what the client owes, what
      the suppliers are owed, and what is actually in the bank because of it. */}
  {/* auto-fit, not Tailwind breakpoints: the host's own stylesheet loads after a plugin's and
      redefines the same responsive utilities, so a widget's sm:/lg: classes lose the cascade. */}
  <div className="grid gap-3" style={{gridTemplateColumns:'repeat(auto-fit,minmax(230px,1fr))'}}>
   {/* How much of the estimate is already spoken for: what the suppliers have been committed,
       against what the chosen scenario says the event costs. */}
   {/* Reading an alternative is allowed, so the tile says which estimate the figure under it is:
       the one the event runs on, or a scenario being compared against it. */}
   <Stat label={b&&data.selectedBudget&&b.id!==data.selectedBudget?'Estimate being read':'Selected estimate'}
     value={b?money(b.total):'—'}
     badge={b&&b.unknown>0?<Badge variant="secondary">{b.unknown} unpriced</Badge>:undefined}
     progress={b&&Number(b.total)>0?Number(data.supplierBilled)/Number(b.total):undefined}
     lines={[
      b&&Number(b.total)>0
        ? `${Math.round(Number(data.supplierBilled)/Number(b.total)*100)}% committed · ${money(Number(b.total)-Number(data.supplierBilled))} left`
        : (b?b.priceBasis:null),
      b&&b.unknown>0?`${b.unknown} ${b.unknown===1?'article':'articles'} still unpriced`:null,
      b&&b.deposit>0?`Deposit ${money(b.deposit)} extra`:null,
      b&&data.selectedBudget&&b.id!==data.selectedBudget?'Not the estimate this event runs on':null,
     ]}/>
   <Stat label="Client" value={money(clientDue)} tone={clientDue>0?'debt':undefined}
     lines={[`Invoiced ${money(data.clientBilled)}`,`Received ${money(data.received)}`]}/>
   <Stat label="Suppliers" value={money(supplierDue)} tone={supplierDue>0?'debt':undefined}
     lines={[`Committed ${money(data.supplierBilled)}`,`Paid ${money(data.paid)}`]}/>
   <Stat label="Cash on this event" value={money(cash)} tone={cash>0?'good':undefined}
     lines={['In minus out, posted payments only']}/>
  </div>

  <Segmented value={tab} onChange={setTab}
    options={['Estimates','Budget','Invoices','Payments','Team'].map(value=>({value,label:value}))}
    className="[&_.t-tabs-pill]:bg-primary [&_[aria-pressed=true]]:text-primary-foreground"/>

  {tab==='Estimates'&&<div className="space-y-3">
   <SectionHeader title="Estimates" count={data.budgets.length}>
    {/* The couple is shown the venues side by side, which is the layout the quotes arrive in. */}
    {data.budgets.length>0&&<Button size="toolbar" variant="subtle" disabled={busy}
      title="All scenarios side by side, as one Excel sheet"
      onClick={()=>void exporting(`/${id}/scenarios/export`,'estimate scenarios.xlsx')}>
     <Icon name="table-2" size={16}/>Export scenarios
    </Button>}
    <Button size="toolbar" variant="subtle" disabled={!can}
      onClick={()=>window.dispatchEvent(new CustomEvent('onno:action',{detail:`onno://main/documents/event_budgets/new?event=${id}`}))}>
     <Icon name="plus" size={16}/>New estimate
    </Button>
   </SectionHeader>
   <p className="text-xs text-muted-foreground">
    {data.budgets.length
      ? 'Compare the quotes side by side, then pick the scenario this event runs on — the chosen one drives the totals above and the expenses below.'
      : 'No estimates yet.'}
   </p>

   {data.budgets.length>0&&<ScenarioComparison id={id} reading={selected} onRead={choose}
     canWrite={can} busy={busy}
     onUse={budget=>void run(async()=>{
      await request(`/${id}/budget`,{budget});
      // Choosing an estimate also reads it: the totals above and the expenses below are the chosen
      // scenario's from now on, and leaving the page reading the old one hides the decision.
      viewing.current=budget;setSelected(budget);setLineId('');
     })}
     onCopy={budget=>void run(async()=>{const r=await request(`/budgets/${budget}/copy`,{});openRecord('documents','EventBudgets',r.id);})}/>}

  </div>}

  {tab==='Budget'&&<div className="space-y-3">
   {/* What the money is actually spent on. A category total says a line is 80% committed; only the
       articles under it say which florist holds the rest, and against which invoice. */}
   <div className="space-y-2 pt-1">
    <SectionHeader title="Where the money goes" count={grouped.length}>
      {grouped.length>0&&<Segmented value={grouping} onChange={setGrouping} size="sm"
       options={[{value:'category',label:'By category'},{value:'supplier',label:'By supplier'},
         {value:'phase',label:'By day'}]}/>}
      {grouped.length>0&&<Button size="toolbar" variant="ghost"
       onClick={()=>setOpenCategories(current=>current.size?new Set():new Set(grouped.map(c=>c.category)))}>
      <Icon name={openCategories.size?'chevrons-down-up':'chevrons-up-down'} size={16}/>
      {openCategories.size?'Collapse all':'Expand all'}
     </Button>}
     {eligible.length>0&&<Select value={lineId} onValueChange={setLineId}>
      <SelectTrigger className="h-8 w-64 text-xs"><SelectValue placeholder="Quoted article…"/></SelectTrigger>
      <SelectContent>{eligible.map((l:Row)=><SelectItem key={l.id} value={l.id}>{l.article} · {l.contractor} · {money(l.amount)}</SelectItem>)}</SelectContent>
     </Select>}
     {eligible.length>0&&<Button size="toolbar" variant="subtle" disabled={!can||!lineId}
       onClick={()=>void run(async()=>{const r=await request(`/budgets/${b!.id}/lines/${lineId}/invoice`,{});openRecord('documents','EventInvoices',r.id);})}>
      <Icon name="file-plus" size={16}/>Commit to supplier
     </Button>}
    </SectionHeader>
    {b&&<p className="text-xs text-muted-foreground">
     Every article in {b.scenario} — what it covers, who is on it, and the supplier invoices raised
     against it. Open a row to read its lines.
     {unpriced>0&&<span className="text-amber-500"> {unpriced} {unpriced===1?'article is':'articles are'} still
      unpriced, so they are in no total on this page.</span>}
    </p>}

    {grouped.length?<div className="space-y-2">
     {/* The column names belong here, once, rather than repeated inside all thirteen rows. */}
     <div className="flex items-center gap-3 px-3 text-[11px] uppercase tracking-wide text-muted-foreground">
      <span className="w-4"/><span className="min-w-0 flex-1"/>
      <span className="flex shrink-0 flex-wrap justify-end gap-x-6 gap-y-1 text-right">
       {['Estimated','Committed','Paid','Left'].map(label=><span key={label} className="w-28">{label}</span>)}
      </span>
     </div>
     {grouped.map(category=><CategoryPanel key={category.category} category={category}
       open={openCategories.has(category.category)}
       onToggle={()=>setOpenCategories(current=>{const next=new Set(current);
        if(next.has(category.category))next.delete(category.category);else next.add(category.category);
        return next;})}/>)}
     {/* The same three figures the cards above carry, added up, so the detail reconciles on screen. */}
     <div className="flex flex-wrap items-center justify-between gap-3 rounded-panel border border-border bg-muted/40 px-3 py-2.5">
      <span className="text-sm font-medium">Total</span>
      <span className="flex flex-wrap gap-6 text-right">
       {([['Estimated',totals.estimated],['Committed',totals.committed],['Paid',totals.paid],
          ['Left',totals.estimated-totals.committed]] as [string,number][]).map(([label,value])=>
        <span key={label} className="w-28">
         <span className="block text-[11px] uppercase tracking-wide text-muted-foreground">{label}</span>
         <span className={`block text-sm font-medium tabular-nums ${label==='Left'&&value<0?'text-destructive':''}`}>
          {money(Math.abs(value))}
         </span>
        </span>)}
      </span>
     </div>
    </div>:<p className="rounded-panel border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
     Choose an estimate to see where its money goes.
    </p>}
    {b&&eligible.length===0&&<p className="text-xs text-muted-foreground">
     To commit an article to a supplier, give it a contractor and a quoted amount in {b.scenario}.
    </p>}
   </div>
   {/* Margin lives here rather than in a tab of its own: it is the same question read from the
       other end — what the couple is quoted, less what the suppliers charge us. */}
   {data.canSeeMargin&&<div className="pt-2"><MarginPanel id={id}/></div>}
  </div>}

  {tab==='Invoices'&&<div className="space-y-3">
   <p className="text-xs text-muted-foreground">
    Client invoices bring money in, supplier invoices take it out. Open a posted invoice and choose
    Record payment to settle it.
   </p>
   <NativeDocuments name="event_invoices" event={id}/>
  </div>}

  {tab==='Payments'&&<div className="space-y-3">
   <p className="text-xs text-muted-foreground">Posted payments move the cash figures above; drafts do not.</p>
   <NativeDocuments name="event_payments" event={id}/>
  </div>}


  {tab==='Team'&&<div className="space-y-3">
   <div className="flex flex-wrap items-center justify-between gap-2">
    <p className="text-xs text-muted-foreground">Everyone working on this event, and what they own.</p>
    <Button size="toolbar" variant="subtle" disabled={!can}
      onClick={()=>window.dispatchEvent(new CustomEvent('onno:action',{detail:`onno://main/catalogs/event_partys/new?event=${id}`}))}>
     <Icon name="user-plus" size={16}/>Add participant
    </Button>
   </div>
   {e.crew.length?<div className="grid gap-3" style={{gridTemplateColumns:'repeat(auto-fill,minmax(280px,1fr))'}}>
    {e.crew.map(member=><div key={member.id} className="flex items-start justify-between gap-3 rounded-panel border border-border bg-card p-4">
     <div className="min-w-0 space-y-1">
      <p className="truncate font-medium">{member.contact}</p>
      <p className="truncate text-xs text-muted-foreground">{member.scope||'No scope recorded'}</p>
      <Badge variant="secondary">{member.role.charAt(0)+member.role.slice(1).toLowerCase()}</Badge>
     </div>
     {member.contactId&&<Button size="toolbar" variant="subtle" onClick={()=>openRecord('catalogs','Contacts',member.contactId!)}>
      <Icon name="arrow-up-right" size={14}/>Open
     </Button>}
    </div>)}
   </div>:<p className="rounded-panel border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
    Nobody is on this event yet.
   </p>}
  </div>}
 </div>;
}
/** The event page: one event, addressed by "?event=<id>" on the /event route. */
function EventPage(){
 const [id,setId]=useState(()=>new URLSearchParams(window.location.search).get('event')||undefined);
 // Tabs keep several events open at once, so the page re-reads its id whenever the route changes.
 useEffect(()=>{
  const read=()=>setId(new URLSearchParams(window.location.search).get('event')||undefined);
  window.addEventListener('popstate',read);
  return()=>window.removeEventListener('popstate',read);
 },[]);
 return <EventWorkspace id={id}/>;
}
registerWidget('plannerEventPage',EventPage);
