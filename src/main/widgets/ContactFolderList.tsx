import {EntityListWidget,registerWidget,useEffect,useState,type WidgetProps} from '@onno/widget-sdk';
// The contacts list, constrained to one inbox folder. A page-level list(...) can carry the feed
// filter but not the New button's destination, so a contractor created from the Contractors page
// would be born a client and vanish from the list it was created in. Reusing the catalog's own
// descriptor keeps the columns, actions and merge selection widget the full Contacts list has.
function findList(value:any):any {
 if(!value||typeof value!=='object')return null;
 if(value.list?.kind==='catalogs')return value.list;
 for(const child of Object.values(value)){const found=findList(child);if(found)return found;}
 return null;
}
function ContactFolderList({widget}:WidgetProps){
 const folder=widget.extraConfig?.folder??'';
 const [list,setList]=useState<any>(null);const [error,setError]=useState('');
 useEffect(()=>{let active=true;setList(null);setError('');
  fetch('/api/divkit/catalogs/contacts',{credentials:'same-origin'}).then(async r=>{
   if(!r.ok)throw new Error('Unable to load contacts');
   const descriptor=findList(await r.json());
   if(!descriptor)throw new Error('Contact list unavailable');
   if(active)setList({...descriptor,embedded:true,fill:true,
    baseFilter:`inbox_folder = '${folder}'`,
    newUrl:descriptor.newUrl?`${descriptor.newUrl}?inboxFolder=${encodeURIComponent(folder)}`:null,
    columns:descriptor.columns.filter((c:any)=>c.fieldName!=='inboxFolder')});
  }).catch(e=>{if(active)setError(e.message);});
  return()=>{active=false;};
 },[folder]);
 return error?<p role="alert" className="text-destructive">{error}</p>:list?<EntityListWidget list={list}/>:<p className="text-muted-foreground">Loading contacts…</p>;
}
registerWidget('plannerContactFolderList',ContactFolderList);
