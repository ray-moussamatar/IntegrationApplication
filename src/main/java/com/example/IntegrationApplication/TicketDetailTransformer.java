package com.example.IntegrationApplication;

import org.springframework.integration.core.GenericTransformer;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;


@Component
public class TicketDetailTransformer implements GenericTransformer<Message<TicketRequest>, Message<String>> {

    @Override
    public Message<String> transform(Message<TicketRequest> message) {
        TicketRequest ticket = message.getPayload();

        if (ticket.title() == null || ticket.title().isEmpty()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        if (ticket.description() == null ||
                ticket.description().isEmpty()) {
            throw new IllegalArgumentException("description cannot be blank");
        }
        if (ticket.priority() > 5 || ticket.priority() < 1) {
            throw new IllegalArgumentException("priority must be between 1 and 5");
        }

        System.out.println("=== All Headers ===");
        message.getHeaders().forEach((key, value) -> {
            System.out.println(key + ": " + value);
        });

        String result = String.format("[%s] %s (priority=%d)",
                ticket.title().toUpperCase(),
                ticket.description(),
                ticket.priority());

        return MessageBuilder.withPayload(result)
                .copyHeaders(message.getHeaders())
                .setHeader("processed_by", "detail-transformer")
                .build();


    }
}
