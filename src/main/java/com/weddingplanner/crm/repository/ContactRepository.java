package com.weddingplanner.crm.repository;
import com.weddingplanner.crm.domain.Contact;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;
@Repository
public interface ContactRepository extends CatalogRepository<Contact> {}
