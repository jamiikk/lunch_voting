package com.example.lunchvoting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public com.example.lunchvoting.util.DistanceUtils distanceUtils() {
        return new com.example.lunchvoting.util.DistanceUtils();
    }
}
