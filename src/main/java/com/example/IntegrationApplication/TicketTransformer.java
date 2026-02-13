package com.example.IntegrationApplication;

import org.springframework.integration.core.GenericTransformer;
import org.springframework.stereotype.Component;

@Component
public class TicketTransformer implements GenericTransformer<TicketRequest, String> {

    @Override
    public String transform(TicketRequest ticket) {

        if (ticket.title() == null || ticket.title().isEmpty()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        if (ticket.description() == null || ticket.description().isEmpty()) {
            throw new IllegalArgumentException("description cannot be blank");
        }
        if (ticket.priority() > 5 || ticket.priority() < 1) {
            throw new IllegalArgumentException("priority must be between 1 and 5");
        }

        return String.format("[%s] %s (priority=%d)",
                ticket.title().toUpperCase(),
                ticket.description(),
                ticket.priority());
    }

}

