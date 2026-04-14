package com.example.lunchvoting.service;

import com.example.lunchvoting.domain.MenuItem;
import com.example.lunchvoting.domain.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenickaScraperService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private static final String CACHE_KEY = "restaurants:brno:v2";
    private static final Pattern MARKER_PATTERN = Pattern.compile("\\['(\\d+)',\\s*'(.*?)',\\s*'(.*?)',\\s*([\\d.]+),\\s*([\\d.]+),");

    public Flux<Restaurant> getRestaurants() {
        return redisTemplate.opsForValue().get(CACHE_KEY)
            .cast(List.class)
            .flatMapMany(list -> Flux.fromIterable((List<Restaurant>) list))
            .switchIfEmpty(scrapeAndCache());
    }

    private Flux<Restaurant> scrapeAndCache() {
        return Mono.fromCallable(() -> Jsoup.connect("https://www.menicka.cz/brno.html").get())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(doc -> {
                // Parse markers for coordinates
                Map<String, double[]> coordsMap = new HashMap<>();
                doc.select("script").forEach(script -> {
                    String html = script.html();
                    if (html.contains("var markers =")) {
                        Matcher matcher = MARKER_PATTERN.matcher(html);
                        while (matcher.find()) {
                            String restaurantId = matcher.group(1);
                            double lat = Double.parseDouble(matcher.group(4));
                            double lon = Double.parseDouble(matcher.group(5));
                            coordsMap.put(restaurantId, new double[]{lat, lon});
                        }
                    }
                });
                log.info("Extracted {} coordinates from markers script", coordsMap.size());

                Elements restaurantElements = doc.select(".menicka_detail");
                return Flux.fromIterable(restaurantElements)
                    .map(element -> {
                        String name = element.select(".hlavicka .nazev a").text();
                        String id = element.select(".hlavicka .nazev a").attr("href");
                        
                        // Extract numeric ID from href (e.g., https://www.menicka.cz/5396-restaurace-sharingham.html -> 5396)
                        String restaurantIdNumeric = "";
                        Pattern idPattern = Pattern.compile("(\\d+)-");
                        Matcher idMatcher = idPattern.matcher(id);
                        if (idMatcher.find()) {
                            restaurantIdNumeric = idMatcher.group(1);
                        }

                        String address = name + ", Brno";
                        
                        double[] coords = coordsMap.get(restaurantIdNumeric);
                        Double lat = coords != null ? coords[0] : null;
                        Double lon = coords != null ? coords[1] : null;

                        Elements menuElements = element.select(".menicka .nabidka_1");
                        Elements priceElements = element.select(".menicka .cena");
                        
                        List<MenuItem> items = new ArrayList<>();
                        for (int i = 0; i < Math.min(menuElements.size(), priceElements.size()); i++) {
                            items.add(new MenuItem(menuElements.get(i).text(), priceElements.get(i).text()));
                        }
                        
                        log.info("Scraped restaurant: {} at {}, lat: {}, lon: {}", name, address, lat, lon);
                        return new Restaurant(id, name, address, lat, lon, items);
                    })
                    .filter(r -> !r.menu().isEmpty())
                    .distinct(Restaurant::id);
            })
            .collectList()
            .flatMapMany(list -> redisTemplate.opsForValue().set(CACHE_KEY, list, Duration.ofHours(4))
                .thenMany(Flux.fromIterable(list)));
    }
}
