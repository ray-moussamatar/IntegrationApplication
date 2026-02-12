package com.example.IntegrationApplication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.file.support.FileExistsMode;

import java.io.File;


@Configuration
@EnableIntegration
public class CsvToJson {

    private String[] headers;

    @Bean
    public IntegrationFlow csvReadingFlow() {
        return IntegrationFlow
                .from(Files.inboundAdapter(new File("input"))
                                .patternFilter("*.csv"),
                        e -> e.poller(Pollers.fixedDelay(1000)))
                .transform(Files.toStringTransformer())
                .split(String.class, payload -> payload.split("\\r?\\n"))
                // Filter first line (header) and store it
                .filter((String line) -> {
                    if (headers == null) {
                        headers = line.split(",");
                        return false; // drop header line
                    }
                    return true; // allow data lines
                })
                .transform(this::convertCsvLineToJson)
                .handle(Files.outboundAdapter(new File("output"))
                        .fileNameGenerator(message -> "output.json")
                        .appendNewLine(true)
                        .fileExistsMode(FileExistsMode.APPEND)
                )
                .get();
    }


    private String convertCsvLineToJson(String line) {
        String[] values = line.split(",");

        StringBuilder json = new StringBuilder("{");

        for (int i = 0; i < headers.length && i < values.length; i++) {
            json.append("\"")
                .append(headers[i].trim())
                .append("\":\"")
                .append(values[i].trim())
                .append("\"");

            if (i < headers.length - 1 && i < values.length - 1) {
                json.append(",");
            }
        }

        json.append("}");
        return json.toString();
    }


}
