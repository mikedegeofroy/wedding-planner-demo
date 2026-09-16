import { useId } from "react";
import { Button, Popover, PopoverContent, PopoverTrigger, Input, Label, Textarea, Select, SelectTrigger, SelectValue, SelectContent, SelectItem, registerExtension, useEffect, useState } from "@onno/widget-sdk";

type Match = { id: string; name: string };
function NewCommunication() {
  const [open,setOpen]=useState(false);
  const [name,setName]=useState("");
  const [phone,setPhone]=useState("");
  const [notes,setNotes]=useState("");
  const [matches,setMatches]=useState<Match[]>([]);
  const [contactId,setContactId]=useState("");
  const [checking,setChecking]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const [requestId,setRequestId]=useState("");
  const id=useId();
  useEffect(()=>{
    if(!open)return;
    setMatches([]);setContactId("");
    if(!/^(\+|00)/.test(phone.trim()) || phone.replace(/\D/g,"").length<7){setChecking(false);return;}
    const controller=new AbortController();setChecking(true);
    const timer=setTimeout(()=>{
      void fetch(`/api/planner/communications/matches?phone=${encodeURIComponent(phone)}`,{credentials:"same-origin",signal:controller.signal})
        .then(async r=>{if(!r.ok)throw new Error("Could not check this phone number. Check the country code and try again.");return r.json() as Promise<Match[]>;})
        .then(items=>{if(controller.signal.aborted)return;setMatches(items);setContactId(items.length===1?items[0].id:"");if(items.length===1)setName(value=>value.trim()?value:items[0].name);})
        .catch(e=>{if(!controller.signal.aborted)setError(e.message);})
        .finally(()=>{if(!controller.signal.aborted)setChecking(false);});
    },250);
    return()=>{clearTimeout(timer);controller.abort();};
  },[phone,open]);
  const save=async()=>{
    if(busy || checking)return;
    setBusy(true);setError("");
    try {
      const token=document.cookie.split(";").map(s=>s.trim()).find(s=>s.startsWith("XSRF-TOKEN="))?.slice(11);
      const response=await fetch("/api/planner/communications",{method:"POST",credentials:"same-origin",headers:{"Content-Type":"application/json",...(token?{"X-XSRF-TOKEN":decodeURIComponent(token)}:{})},body:JSON.stringify({requestId,name,phone,notes,contactId:contactId||null})});
      if(!response.ok){const data=await response.json().catch(()=>({}));throw new Error(data.detail||data.message||"Unable to save the call. Please try again.");}
      const result=await response.json();setOpen(false);setRequestId("");setName("");setPhone("");setNotes("");
      window.dispatchEvent(new CustomEvent("onno:action",{detail:`onno://inbox?conversation=${encodeURIComponent(result.conversationId)}`}));
    }catch(e){setError((e as Error).message);}finally{setBusy(false);}
  };
  return <div className="flex justify-end">
    <Popover open={open} onOpenChange={value=>{if(!busy){setOpen(value);if(value){if(!requestId)setRequestId(crypto.randomUUID());setError("");}}}}>
      <PopoverTrigger asChild><Button size="toolbar" variant="subtle">New communication</Button></PopoverTrigger>
      <PopoverContent align="end" className="w-[440px] max-w-[calc(100vw-32px)] max-h-[80vh] overflow-y-auto p-5" aria-label="New communication">
        <div className="mb-4 space-y-1"><h2 className="text-base font-semibold">New communication</h2><p className="text-sm text-muted-foreground">Record a phone call with a new or existing client.</p></div>
        <form className="space-y-3" onSubmit={e=>{e.preventDefault();void save();}}>
          <div className="space-y-1"><Label htmlFor={`${id}-type`}>Communication</Label><Select value="phone" disabled={busy}><SelectTrigger id={`${id}-type`}><SelectValue /></SelectTrigger><SelectContent><SelectItem value="phone">Phone call</SelectItem></SelectContent></Select></div>
          <div className="space-y-1"><Label htmlFor={`${id}-phone`}>Phone number</Label><Input id={`${id}-phone`} type="tel" autoComplete="tel" required maxLength={80} placeholder="+44 7700 900123" value={phone} onChange={e=>{setPhone(e.target.value);setError("");}} disabled={busy}/><p className="text-xs text-muted-foreground">Include the country code so we can check for an existing client.</p></div>
          {checking&&<p role="status" className="text-xs text-muted-foreground">Checking contacts…</p>}
          {matches.length===1&&<p role="status" className="rounded-field bg-muted p-3 text-sm">Existing client: <strong>{matches[0].name}</strong>. This call will be added to their history.</p>}
          {matches.length>1&&<div className="space-y-1"><Label>Several clients share this number</Label><Select value={contactId} onValueChange={setContactId} disabled={busy}><SelectTrigger aria-label="Existing client"><SelectValue placeholder="Choose the caller"/></SelectTrigger><SelectContent>{matches.map(m=><SelectItem key={m.id} value={m.id}>{m.name}</SelectItem>)}</SelectContent></Select></div>}
          <div className="space-y-1"><Label htmlFor={`${id}-name`}>Caller’s name</Label><Input id={`${id}-name`} required maxLength={200} value={name} onChange={e=>setName(e.target.value)} disabled={busy} placeholder="Name or couple’s names"/></div>
          <div className="space-y-1"><Label htmlFor={`${id}-notes`}>Call notes</Label><Textarea id={`${id}-notes`} required maxLength={7000} rows={3} value={notes} onChange={e=>setNotes(e.target.value)} disabled={busy} placeholder="What did you discuss? What happens next?"/></div>
          {error&&<p role="alert" className="text-sm text-destructive">{error}</p>}
          <div className="flex justify-end gap-2"><Button type="button" variant="ghost" disabled={busy} onClick={()=>setOpen(false)}>Cancel</Button><Button type="submit" disabled={busy||checking||!name.trim()||!phone.trim()||!notes.trim()||(matches.length>1&&!contactId)}>{busy?"Saving…":"Save communication"}</Button></div>
        </form>
      </PopoverContent>
    </Popover>
  </div>;
}
registerExtension({
  id: "planner.new-communication",
  slot: "entity.list.actions",
  visible: context => context.kind === "catalogs" && context.name === "crm_conversations",
  component: NewCommunication,
});
