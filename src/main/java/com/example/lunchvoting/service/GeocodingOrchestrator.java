package com.example.lunchvoting.service;

import com.example.lunchvoting.domain.Restaurant;
import com.example.lunchvoting.domain.RestaurantLocation;
import com.example.lunchvoting.repository.RestaurantLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeocodingOrchestrator {

    private final GeocodingService geocodingService;
    private final MenickaScraperService scraperService;
    private final RestaurantLocationRepository locationRepository;

    /**
     * Fetches restaurants and geocodes those that don't have coordinates.
     * Uses a database (Postgres) to store geolocations permanently.
     */
    public Flux<Restaurant> getGeocodedRestaurants() {
        return scraperService.getRestaurants()
            .concatMap(restaurant -> {
                if (restaurant.latitude() != null && restaurant.longitude() != null) {
                    return Mono.just(restaurant);
                }
                
                log.info("Checking DB for restaurant: {}", restaurant.name());
                return locationRepository.findById(restaurant.name())
                    .map(location -> {
                        log.info("Found {} in DB: {}, {}", restaurant.name(), location.getLatitude(), location.getLongitude());
                        return new Restaurant(
                            restaurant.id(),
                            restaurant.name(),
                            restaurant.address(),
                            location.getLatitude(),
                            location.getLongitude(),
                            restaurant.menu()
                        );
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.info("Restaurant {} not in DB, geocoding address: {}", restaurant.name(), restaurant.address());
                        return geocodingService.getCoordinates(restaurant.address() + ", Brno")
                            .flatMap(coords -> {
                                log.info("Geocoded {} to {}, {}. Saving to DB...", restaurant.name(), coords.latitude(), coords.longitude());
                                return locationRepository.save(new RestaurantLocation(
                                    restaurant.name(),
                                    coords.latitude(),
                                    coords.longitude()
                                ))
                                .doOnNext(saved -> log.info("Successfully saved {} to DB", saved.getName()))
                                .doOnError(e -> log.error("Failed to save {} to DB", restaurant.name(), e))
                                .thenReturn(new Restaurant(
                                    restaurant.id(),
                                    restaurant.name(),
                                    restaurant.address(),
                                    coords.latitude(),
                                    coords.longitude(),
                                    restaurant.menu()
                                ));
                            })
                            .defaultIfEmpty(restaurant)
                            .doOnNext(r -> {
                                if (r.latitude() == null) {
                                    log.warn("Could not geocode restaurant: {}", restaurant.name());
                                }
                            })
                            .delayElement(Duration.ofSeconds(1));
                    }));
            });
    }
}
