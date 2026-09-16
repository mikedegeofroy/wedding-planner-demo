package com.weddingplanner.crm.events.service;

import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;

/** Domain lifecycle instances are framework-created; this bridge resolves cross-record checks. */
@Component
public class EventRules implements ApplicationContextAware {
    private static ApplicationContext context;
    @Override public void setApplicationContext(ApplicationContext value){context=value;}
    public static boolean activeArticle(Ref<BudgetArticle> ref){return ref!=null&&context.getBean(BudgetArticleRepository.class).findActiveById(ref.id()).isPresent();}
    public static boolean stableInvoiceParty(EventInvoice invoice){
        if(invoice.getId()==null)return true;
        var old=context.getBean(EventInvoiceRepository.class).findById(invoice.getId()).orElse(null);
        if(old==null||!old.isPosted())return true;
        return java.util.Objects.equals(old.getEvent(),invoice.getEvent())&&old.getDirection()==invoice.getDirection()&&java.util.Objects.equals(old.getCounterparty(),invoice.getCounterparty());
    }
    public static boolean activeEvent(Ref<EventProject> ref){return ref!=null&&context.getBean(EventProjectRepository.class).findActiveById(ref.id()).isPresent();}
    public static boolean activeContact(Ref<Contact> ref){return ref!=null&&context.getBean(ContactRepository.class).findActiveById(ref.id()).isPresent();}
    public static boolean budgetBelongs(Ref<EventBudget> ref, UUID event){return context.getBean(EventBudgetRepository.class).findActiveById(ref.id()).map(b->b.getEvent()!=null&&b.getEvent().id().equals(event)).orElse(false);}
    public static EventInvoice invoice(Ref<EventInvoice> ref){return ref==null?null:context.getBean(EventInvoiceRepository.class).findActiveById(ref.id()).orElse(null);}
    public static boolean invoicePosted(Ref<EventInvoice> ref){var i=invoice(ref);return i!=null&&i.isPosted()&&activeEvent(i.getEvent());}
}
