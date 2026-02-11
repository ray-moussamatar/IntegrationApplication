package com.example.IntegrationApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

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
				.handle(((payload, headers) ->  {
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

}
