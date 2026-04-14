package com.example.lunchvoting.service;

import com.example.lunchvoting.domain.MenuItem;
import com.example.lunchvoting.domain.Restaurant;
import com.example.lunchvoting.util.DistanceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final DistanceUtils distanceUtils;

    public Flux<Restaurant> getRestaurants() {
        return Flux.just(
            new Restaurant("rest-1", "Seven Food", "Purkyňova 127, Brno", 49.227, 16.575, List.of(
                new MenuItem("Svíčková na smetaně", "155 Kč"),
                new MenuItem("Vepřový řízek, brambor", "145 Kč")
            )),
            new Restaurant("rest-2", "Restobar Onyx", "Hrnčířská 6, Brno", 49.208, 16.602, List.of(
                new MenuItem("Burger s trhaným masem", "189 Kč"),
                new MenuItem("Caesar salát", "165 Kč")
            )),
            new Restaurant("rest-3", "Pizzerie Basilico", "Drobného 28, Brno", 49.204, 16.613, List.of(
                new MenuItem("Pizza Margherita", "140 Kč"),
                new MenuItem("Lasagne Bolognese", "175 Kč")
            ))
        );
    }

    public Flux<Restaurant> calculateDistancesAndSort(Flux<Restaurant> restaurants, double targetLat, double targetLon) {
        return restaurants
            .collectList()
            .flatMapMany(list -> {
                list.sort((r1, r2) -> {
                    if (r1.latitude() == null || r1.longitude() == null) return 1;
                    if (r2.latitude() == null || r2.longitude() == null) return -1;
                    
                    double d1 = distanceUtils.calculateDistance(targetLat, targetLon, r1.latitude(), r1.longitude());
                    double d2 = distanceUtils.calculateDistance(targetLat, targetLon, r2.latitude(), r2.longitude());
                    return Double.compare(d1, d2);
                });
                return Flux.fromIterable(list);
            });
    }
}
