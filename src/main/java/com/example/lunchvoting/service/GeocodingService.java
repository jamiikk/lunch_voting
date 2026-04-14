package com.example.lunchvoting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class GeocodingService {

    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private static final String CACHE_PREFIX = "geocoding:";

    public GeocodingService(WebClient.Builder webClientBuilder, ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.webClient = webClientBuilder
            .baseUrl("https://photon.komoot.io")
            .defaultHeader("User-Agent", "lunch-voting-app/1.0 (dev@lunchvoting.local)")
            .build();
        this.redisTemplate = redisTemplate;
    }

    public Mono<Coordinates> getCoordinates(String address) {
        return search(address)
            .next()
            .map(s -> new Coordinates(s.latitude(), s.longitude()));
    }

    public Flux<AddressSuggestion> search(String address) {
        if (address == null || address.isBlank()) {
            log.warn("Null or blank address passed for geocoding.");
            return Flux.empty();
        }
        
        // Only search if the user typed at least 2 characters (excluding the ", Brno" suffix)
        String realAddress = address.replace(", Brno", "").trim();
        if (realAddress.length() < 2) {
            log.warn("Address '{}' too short for geocoding (minimal 2 chars).", realAddress);
            return Flux.empty();
        }

        String key = CACHE_PREFIX + address.toLowerCase().trim();
        return redisTemplate.opsForValue().get(key)
            .cast(List.class)
            .flatMapMany(list -> Flux.fromIterable((List<?>) list))
            .map(item -> {
                if (item instanceof AddressSuggestion) {
                    return (AddressSuggestion) item;
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.convertValue(item, AddressSuggestion.class);
            })
            .switchIfEmpty(fetchAndCache(address, key));
    }

    private Flux<AddressSuggestion> fetchAndCache(String address, String key) {
        return Mono.delay(Duration.ofMillis(500))
            .thenMany(Flux.defer(() -> {
                String finalAddress = address.replace(", Brno", "").trim();
                log.info("Geocoding API search for: {} (original: {})", finalAddress, address);
                return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/api/")
                        .queryParam("q", finalAddress + ", Brno, Czechia")
                        .queryParam("limit", 5)
                        .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .doOnNext(node -> log.info("Geocoding API JSON response for {}: {}", address, node))
                    .flatMapMany(jsonNode -> {
                        if (jsonNode.has("features") && jsonNode.get("features").isArray()) {
                            var features = jsonNode.get("features");
                            log.info("API returned {} suggestions for: {}", features.size(), address);
                            if (features.isEmpty()) {
                                log.warn("No geocoding features found for: {}", address);
                            }
                            return Flux.fromIterable(features)
                                .map(node -> {
                                    var props = node.get("properties");
                                    var coords = node.get("geometry").get("coordinates");
                                    String name = props.has("name") ? props.get("name").asText() : "";
                                    String street = props.has("street") ? props.get("street").asText() : "";
                                    String display = name.isEmpty() ? street : (name + (street.isEmpty() ? "" : ", " + street));
                                    if (display.isEmpty() && props.has("city")) display = props.get("city").asText();
                                    return new AddressSuggestion(
                                        display,
                                        coords.get(1).asDouble(),
                                        coords.get(0).asDouble()
                                    );
                                });
                        }
                        return Flux.empty();
                    })
                    .collectList()
                    .flatMapMany(list -> {
                        if (list.isEmpty()) return Flux.empty();
                        return redisTemplate.opsForValue().set(key, list, Duration.ofDays(1))
                            .thenMany(Flux.fromIterable(list));
                    });
            }))
            .onErrorResume(e -> {
                log.error("Geocoding API FATAL error for: {}. Error message: {}", address, e.getMessage(), e);
                return Flux.empty();
            });
    }

    public record Coordinates(double latitude, double longitude) {}
    public record AddressSuggestion(String displayName, double latitude, double longitude) {}
}
