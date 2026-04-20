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
import org.springframework.web.server.ServerWebExchange;

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
    public String createSession(ServerWebExchange exchange) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        var uri = exchange.getRequest().getURI();
        String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
        String path = "/vote/" + sessionId;
        String fullUrl = baseUrl + path;

        return "<div class=\"url-wrapper\">" +
               "<input type=\"text\" id=\"voting-url\" value=\"" + fullUrl + "\" readonly onclick=\"this.select()\" " +
               "style=\"flex-grow: 1; background: rgba(15, 23, 42, 0.5); border: 1px solid var(--primary); padding: 14px; border-radius: 12px; color: white; text-align: center; font-family: monospace; outline: none;\">" +
               "<button onclick=\"copyUrl()\" class=\"copy-btn\" title=\"Copy to clipboard\">" +
               "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"9\" width=\"13\" height=\"13\" rx=\"2\" ry=\"2\"></rect><path d=\"M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1\"></path></svg>" +
               "</button>" +
               "</div>" +
               "<div id=\"copy-success\">Copied to clipboard!</div>" +
               "<a href=\"" + path + "\" class=\"generate-btn\" style=\"text-decoration: none; margin-top: 10px; text-align: center;\">Go to Voting Room</a>";
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
    public Mono<String> vote(ServerWebExchange exchange,
                            @RequestHeader("X-Voter-GUID") String voterGuid) {
        return exchange.getFormData()
            .flatMap(formData -> {
                String restaurantIdsJoined = formData.getFirst("restaurantId");
                String sessionId = formData.getFirst("sessionId");
                
                // Fallback to query params if not in form data
                if (restaurantIdsJoined == null) {
                    restaurantIdsJoined = exchange.getRequest().getQueryParams().getFirst("restaurantId");
                }
                if (sessionId == null) {
                    sessionId = exchange.getRequest().getQueryParams().getFirst("sessionId");
                }
                
                if (sessionId == null) sessionId = "global";
                if (restaurantIdsJoined == null || restaurantIdsJoined.isBlank()) {
                    return Mono.just("No restaurants selected.");
                }

                java.util.List<String> restaurantIds = java.util.Arrays.asList(restaurantIdsJoined.split(","));
                String ipAddress = exchange.getRequest().getRemoteAddress() != null 
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() 
                    : "unknown";
                    
                return voteService.voteMultiple(restaurantIds, voterGuid, sessionId, ipAddress)
                    .map(success -> success ? "Votes recorded!" : "Unable to vote (already voted or rate limited).");
            });
    }

    @PostMapping("/api/votes/reset")
    @ResponseBody
    public Mono<String> reset(ServerWebExchange exchange,
                             @RequestHeader("X-Voter-GUID") String voterGuid) {
        return exchange.getFormData().flatMap(formData -> {
            String sessionId = formData.getFirst("sessionId");
            if (sessionId == null) {
                sessionId = exchange.getRequest().getQueryParams().getFirst("sessionId");
            }
            if (sessionId == null) sessionId = "global";
            
            return voteService.resetVote(voterGuid, sessionId)
                .map(success -> success ? "Votes reset successfully!" : "No votes to reset or already reset.");
        });
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
