package com.example.lunchvoting.service;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;

@Service
public class VoteService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private static final String VOTE_KEY_PREFIX = "lunch-votes:";
    private static final String USER_VOTE_KEY_PREFIX = "user-voted:";
    
    private final Sinks.Many<Map<Object, Object>> sink = Sinks.many().multicast().directBestEffort();

    public VoteService(ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> vote(String restaurantId, String userId, String sessionId) {
        String userKey = USER_VOTE_KEY_PREFIX + sessionId + ":" + userId;
        String totalsKey = VOTE_KEY_PREFIX + sessionId + ":totals";
        
        return redisTemplate.hasKey(userKey)
            .flatMap(hasVoted -> {
                if (hasVoted) {
                    return Mono.just(false);
                }
                return redisTemplate.opsForValue().set(userKey, restaurantId)
                    .then(redisTemplate.opsForHash().increment(totalsKey, restaurantId, 1L))
                    .then(getSnapshot(sessionId))
                    .doOnNext(snapshot -> {
                        Map<Object, Object> message = new HashMap<>(snapshot);
                        message.put("_sessionId", sessionId);
                        sink.tryEmitNext(message);
                    })
                    .thenReturn(true);
            });
    }

    public Flux<Map<Object, Object>> getResults(String sessionId) {
        return getSnapshot(sessionId)
            .concatWith(sink.asFlux().filter(msg -> sessionId.equals(msg.get("_sessionId"))));
    }

    private Mono<Map<Object, Object>> getSnapshot(String sessionId) {
        String totalsKey = VOTE_KEY_PREFIX + sessionId + ":totals";
        return redisTemplate.opsForHash().entries(totalsKey)
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
