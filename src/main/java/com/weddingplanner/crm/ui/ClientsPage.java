package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.InboxFolder;
import org.springframework.stereotype.Component;

@Component
public class ClientsPage extends ContactFolderPage {

    public ClientsPage() {
        super(InboxFolder.CLIENTS);
    }

    @Override
    public String route() {
        return "/clients";
    }
}
