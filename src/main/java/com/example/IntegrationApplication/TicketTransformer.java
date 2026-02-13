package com.example.IntegrationApplication;

import org.springframework.integration.core.GenericTransformer;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class TicketTransformer implements GenericTransformer<TicketRequest, String> {

    @Override
    public String transform(TicketRequest ticket) {
        return String.format("[%s] %s (priority=%d)",
                ticket.title().toUpperCase(),
                ticket.description(),
                ticket.priority());
    }

}

