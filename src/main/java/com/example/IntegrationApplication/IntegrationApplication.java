package com.example.IntegrationApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;

import java.time.Instant;

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
	MessageChannel atoc(){
		return new DirectChannel();
//		return MessageChannels.direct().getObject();
	}
	@Bean
	IntegrationFlow Flow() {
		return IntegrationFlow
				.from((MessageSource<String>) () -> MessageBuilder.withPayload("hello World @ " + Instant.now()+ "!").build(), poller -> poller.poller(pm -> pm.fixedRate(1000)))

				.channel(atob())
				.get();
	}

	@Bean
	IntegrationFlow flow1 (){
		return IntegrationFlow
				.from(atob())
				.handle((GenericHandler<String>) (payload, _) -> {
					System.out.println("the payload is " + payload);
					return null;
				})
				.get();
	}

}
