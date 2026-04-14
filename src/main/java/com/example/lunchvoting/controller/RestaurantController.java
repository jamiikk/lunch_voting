package com.example.lunchvoting.controller;

import com.example.lunchvoting.domain.Restaurant;
import com.example.lunchvoting.service.GeocodingOrchestrator;
import com.example.lunchvoting.service.GeocodingService;
import com.example.lunchvoting.service.MenickaScraperService;
import com.example.lunchvoting.service.VoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.spring6.context.webflux.IReactiveDataDriverContextVariable;
import org.thymeleaf.spring6.context.webflux.ReactiveDataDriverContextVariable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class RestaurantController {

    private final MenickaScraperService scraperService;
    private final GeocodingOrchestrator geocodingOrchestrator;
    private final com.example.lunchvoting.service.RestaurantService restaurantService;
    private final VoteService voteService;
    private final GeocodingService geocodingService;

    @GetMapping("/")
    public Mono<String> landing() {
        return Mono.just("landing");
    }

    @GetMapping("/vote")
    public Mono<String> index(@RequestParam(required = false) Double lat, 
                              @RequestParam(required = false) Double lon,
                              @RequestParam(required = false) String address,
                              Model model) {
        return handleVotePage("global", lat, lon, address, model);
    }

    @GetMapping("/vote/{sessionId}")
    public Mono<String> sessionIndex(@PathVariable String sessionId,
                                     @RequestParam(required = false) Double lat, 
                                     @RequestParam(required = false) Double lon,
                                     @RequestParam(required = false) String address,
                                     Model model) {
        return handleVotePage(sessionId, lat, lon, address, model);
    }

    private Mono<String> handleVotePage(String sessionId, Double lat, Double lon, String address, Model model) {
        model.addAttribute("sessionId", sessionId);
        
        if (address != null && !address.isBlank()) {
            return geocodingService.getCoordinates(address + ", Brno")
                .map(coords -> {
                    setupModel(model, coords.latitude(), coords.longitude(), address);
                    return "index";
                })
                .switchIfEmpty(Mono.defer(() -> {
                    setupModel(model, 49.195, 16.608, address);
                    model.addAttribute("error", "Address not found, showing Brno center.");
                    return Mono.just("index");
                }));
        }

        double finalLat = lat != null ? lat : 49.195;
        double finalLon = lon != null ? lon : 16.608;
        setupModel(model, finalLat, finalLon, null);
        return Mono.just("index");
    }

    @PostMapping("/api/sessions")
    @ResponseBody
    public String createSession() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String url = "/vote/" + sessionId;
        return "<input type=\"text\" id=\"voting-url\" value=\"" + url + "\" readonly onclick=\"this.select()\" " +
               "style=\"background: rgba(15, 23, 42, 0.5); border: 1px solid var(--glass-border); padding: 14px; border-radius: 12px; color: white; text-align: center; font-family: monospace; border: 1px solid var(--primary);\">" +
               "<a href=\"" + url + "\" class=\"generate-btn\" style=\"text-decoration: none; margin-top: 10px;\">Go to Voting Room</a>";
    }

    @GetMapping("/api/address/suggestions")
    public Mono<String> suggestions(@RequestParam String address, Model model) {
        return geocodingService.search(address + ", Brno")
            .collectList()
            .doOnNext(list -> model.addAttribute("suggestions", list))
            .thenReturn("index :: address-options");
    }

    private void setupModel(Model model, double lat, double lon, String address) {
        // Show top 50 closest restaurants
        Flux<Restaurant> sortedRestaurants = restaurantService.calculateDistancesAndSort(
            geocodingOrchestrator.getGeocodedRestaurants(),
            lat, lon
        ).take(50);

        IReactiveDataDriverContextVariable reactiveData = 
            new ReactiveDataDriverContextVariable(sortedRestaurants, 1);
        model.addAttribute("restaurants", reactiveData);
        model.addAttribute("targetLat", lat);
        model.addAttribute("targetLon", lon);
        model.addAttribute("address", address);
    }

    @PostMapping("/api/votes")
    @ResponseBody
    public Mono<String> vote(@RequestParam String restaurantId, @RequestParam(required = false, defaultValue = "global") String sessionId) {
        // Simple session-based ID for demo
        String userId = UUID.randomUUID().toString(); 
        return voteService.vote(restaurantId, userId, sessionId)
            .map(success -> success ? "Vote recorded!" : "You already voted!");
    }

    @GetMapping("/api/menus")
    @ResponseBody
    public Flux<Restaurant> getMenus() {
        return scraperService.getRestaurants();
    }

    @GetMapping(value = "/api/votes/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<Map<Object, Object>> liveVotes(@RequestParam(required = false, defaultValue = "global") String sessionId) {
        return voteService.getResults(sessionId);
    }
}
