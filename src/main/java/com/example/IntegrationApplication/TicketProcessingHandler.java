package com.example.IntegrationApplication;

import org.springframework.integration.core.GenericHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class TicketProcessingHandler implements GenericHandler<String> {

    @Override
    public Object handle(String payload, MessageHeaders headers) {
        System.out.println("=== Ticket Received ===");
        System.out.println("payload: " + payload);
        System.out.println("Type: " +
                headers.get("ticket_type"));
        System.out.println("source: " +
                headers.get("source"));
        return "Ticket Processed: " + payload;

    }
}
