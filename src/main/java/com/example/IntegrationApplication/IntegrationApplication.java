package com.example.IntegrationApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;

@SpringBootApplication
public class IntegrationApplication {

	 static void main(String[] args) {
		SpringApplication.run(IntegrationApplication.class, args);
	}

	@Bean
	MessageChannel atob(){
		return MessageChannels.direct().getObject();
	}

	@Bean
	MessageChannel schedulerChannel(){
		return MessageChannels.direct().getObject();
	}

	@Bean
	MessageChannel gatewayChannel(){
		return MessageChannels.direct().getObject();
	}

	@Bean
	MessageChannel adapterChannel(){
		return MessageChannels.direct().getObject();
	}

	// Direct Flow
	@Bean
	IntegrationFlow Flow() {
		return IntegrationFlow
				.from((MessageSource<String>) () ->
						MessageBuilder.withPayload("Scheduled Message").build(),
						poller -> poller.poller(pm -> pm.fixedRate(1000)))
				.enrichHeaders(h -> h.header("source", "scheduler"))
				.channel("atob")
				.get();
	}

	@Bean
	IntegrationFlow flow1 (){
		return IntegrationFlow
				.from("schedulerChannel")
				.log()
				.handle((GenericHandler<String>) (payload, _) -> {
					System.out.println("the payload is " + payload);
					return null;
				})
				.get();
	}

	// inboundGateway
	@Bean
	IntegrationFlow httpGatewayFlow(){
		 return IntegrationFlow
				 .from(Http.inboundGateway("/gateway")
						 .requestMapping(r -> r.methods(HttpMethod.POST))
						 .requestPayloadType(String.class))
				 .enrichHeaders(h -> h.header("source", "gateway"))
				 .channel("atob")
				 .get();
	}

	@Bean
	IntegrationFlow processingGatewayFlow() {
		return IntegrationFlow
				.from("gatewayChannel")
				.handle(( payload, _) -> {
					System.out.println("Processing payload from gateway: " + payload);
					return "Processed Gateway: " + payload;
				})
				.get();
	}

	// inboundAdapter
	@Bean
	IntegrationFlow httpAdapterFlow () {
		return  IntegrationFlow
				.from(Http.inboundChannelAdapter("/adapter")
						.requestMapping(r -> r.methods(HttpMethod.POST))
						.requestPayloadType(String.class)
						.statusCodeFunction(_ -> HttpStatus.OK.value())
				)
				.enrichHeaders(h -> h.header("source", "adapter"))
				.channel("atob")
				.get();
	}

	@Bean
	IntegrationFlow processingAdapterFlow() {
		return IntegrationFlow
				.from("adapterChannel")
				.handle(((payload, _) ->  {
					System.out.println("Processing payload: " + payload);
					return null;
				}))
				.get();
	}

	@Bean
	IntegrationFlow defaultProcessingFlow() {
		return IntegrationFlow
				.from("defaultChannel")
				.handle((payload, _) ->  {
					System.out.println("Default payload: " + payload);
					return null;
				})
				.get();
	}


	// router
	@Bean
	IntegrationFlow routerFlow() {
		return IntegrationFlow
				.from("atob")
				.route(
						Message.class,
						m -> m.getHeaders().get("source"),
						r -> r.channelMapping("gateway", "gatewayChannel")
								.channelMapping("adapter", "adapterChannel")
								.channelMapping("scheduler", "schedulerChannel")
						.defaultOutputChannel("defaultChannel"))
				.get();
	}


	@Bean
	MessageChannel ticketChannel() {
		return MessageChannels.direct().getObject();
	}

	/*
	How it all connects — the diagram

                           curl POST /api/tickets/bug
                           {"title":"Login broken", ...}
                                      |
                                      v
    ┌─────────────────────────────────────────────────────────────┐
    │            IntegrationInboundFlow (line 163)                │
    │                                                             │
    │  1. HTTP Inbound Gateway                                    │
    │     ┌───────────────────────────────────────────────┐       │
    │     │ endpoint: /api/tickets/{ticket_type}          │       │
    │     │ method:   POST only                           │       │
    │     │ consumes: application/json only               │       │
    │     │ payload:  JSON --> TicketRequest (auto-deser)  │       │
    │     │ header:   "ticket_type" = "bug" (from URL)    │       │
    │     └───────────────────────────┬───────────────────┘       │
    │                                 │                           │
    │     Message at this point:                                  │
    │       payload = TicketRequest{title="Login broken",...}     │
    │       headers = {ticket_type="bug"}                         │
    │                                 │                           │
    │  2. .log(INFO, "Ticket.Inbound")                            │
    │     └─> prints to console with category name                │
    │                                 │                           │
    │  3. .transform(TicketTransformer)                           │
    │     ┌───────────────────────────┴───────────────────┐       │
    │     │ TicketRequest ──> "[BUG] Login broken (p=1)"  │       │
    │     └───────────────────────────┬───────────────────┘       │
    │                                 │                           │
    │     Message at this point:                                  │
    │       payload = "[BUG] Login broken (priority=1)"  (String) │
    │       headers = {ticket_type="bug"}                         │
    │                                 │                           │
    │  4. .enrich(header("source", "ticket-api"))                 │
    │     └─> adds source header to the message                   │
    │                                 │                           │
    │     Message at this point:                                  │
    │       payload = "[BUG] Login broken (priority=1)"           │
    │       headers = {ticket_type="bug", source="ticket-api"}    │
    │                                 │                           │
    │  5. .channel("ticketChannel")                               │
    │     └─> sends message to ticketChannel                      │
    └─────────────────────────────────┬───────────────────────────┘
                                      │
                                      v
                ┌──── ticketChannel (line 146) ────┐
                │     DirectChannel (synchronous)  │
                └──────────────┬───────────────────┘
                               │
                               v
    ┌──────────────────────────────────────────────────────┐
    │         ticketProcessingFlow (line 150)               │
    │                                                      │
    │  .handle() prints:                                   │
    │    === Ticket Received ===                           │
    │    payload: [BUG] Login broken (priority=1)          │
    │    Type: bug                                         │
    │    source: ticket-api                                │
    │                                                      │
    │  Returns: "Ticket Processed: ..."                    │
    │  (goes back as HTTP response because we used         │
    │   inboundGateway, which is request/reply)            │
    └──────────────────────────────────────────────────────┘
                               │
                               v
                      HTTP 200 Response
                      "Ticket Processed: [BUG] Login broken (priority=1)"
	 */

	@Bean
	MessageChannel ticketErrorChannel() {
		return MessageChannels.direct().getObject();
	}

	@Bean
	IntegrationFlow ticketErrorFlow() {
		return IntegrationFlow
				.from("ticketErrorChannel")
				.handle((payload, headers) -> {
					Throwable cause = ((MessagingException) payload).getCause();
					while (cause.getCause() != null) {
						cause = cause.getCause();
					}
					return MessageBuilder.withPayload("Error: " + cause.getMessage())
							.setHeader(org.springframework.integration.http.HttpHeaders.STATUS_CODE, 400)
							.build();
				})
				.get();
	}

	@Bean IntegrationFlow ticketProcessingFlow(TicketProcessingHandler ticketProcessingHandler) {
		 return IntegrationFlow
				 .from("ticketChannel")
				 .handle(ticketProcessingHandler)
				 .get();
	}

	@Bean
	IntegrationFlow IntegrationInboundFlow(TicketTransformer ticketTransformer){
		 return IntegrationFlow
				 .from(Http.inboundGateway("/api/tickets/{ticket_type}")
						 .requestMapping(r -> r.methods(HttpMethod.POST)
								 .consumes(MediaType.APPLICATION_JSON_VALUE))
						 .headerExpression("ticket_type", "#pathVariables.ticket_type")
						 .requestPayloadType(TicketRequest.class)
						 .errorChannel("ticketErrorChannel")
				 )
				 .log(LoggingHandler.Level.INFO, "Ticket.Inbound")
				 .transform(ticketTransformer)
				 .enrich(e -> e.header("source", "ticket-api"))
				 .channel("ticketChannel")
				 .get();
	}
}
