package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.InboxFolder;
import org.springframework.stereotype.Component;

@Component
public class ContractorsPage extends ContactFolderPage {

    public ContractorsPage() {
        super(InboxFolder.CONTRACTORS);
    }

    @Override
    public String route() {
        return "/contractors";
    }
}
