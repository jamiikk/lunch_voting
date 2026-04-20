package com.example.lunchvoting.service;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoteService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private static final String VOTE_KEY_PREFIX = "lunch-votes:";
    
    private final Sinks.Many<Map<Object, Object>> sink = Sinks.many().multicast().directBestEffort();

    public VoteService(ReactiveRedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> voteMultiple(List<String> restaurantIds, String voterGuid, String sessionId, String ipAddress) {
        if (restaurantIds == null || restaurantIds.isEmpty()) return Mono.just(false);
        
        String votersKey = "poll:" + sessionId + ":voters";
        String choicesKey = "voter:" + sessionId + ":" + voterGuid + ":choices";
        String ratelimitKey = "ratelimit:ip:" + ipAddress;
        String totalsKey = VOTE_KEY_PREFIX + sessionId + ":totals";
        
        return redisTemplate.hasKey(ratelimitKey)
            .flatMap(isRateLimited -> {
                if (isRateLimited) return Mono.just(false);
                
                return redisTemplate.opsForSet().isMember(votersKey, voterGuid)
                    .flatMap(alreadyVoted -> {
                        if (Boolean.TRUE.equals(alreadyVoted)) return Mono.just(false);
                        
                        return redisTemplate.opsForValue().set(ratelimitKey, "1", Duration.ofSeconds(2))
                            .then(redisTemplate.opsForSet().add(votersKey, voterGuid))
                            .then(redisTemplate.opsForSet().add(choicesKey, restaurantIds.toArray()))
                            .then(Flux.fromIterable(restaurantIds)
                                .flatMap(id -> redisTemplate.opsForHash().increment(totalsKey, id, 1L))
                                .then())
                            .then(getSnapshot(sessionId))
                            .doOnNext(snapshot -> {
                                Map<Object, Object> message = new HashMap<>(snapshot);
                                message.put("_sessionId", sessionId);
                                sink.tryEmitNext(message);
                            })
                            .thenReturn(true);
                    });
            });
    }

    public Mono<Boolean> resetVote(String voterGuid, String sessionId) {
        String votersKey = "poll:" + sessionId + ":voters";
        String choicesKey = "voter:" + sessionId + ":" + voterGuid + ":choices";
        String totalsKey = VOTE_KEY_PREFIX + sessionId + ":totals";

        return redisTemplate.opsForSet().members(choicesKey)
            .map(Object::toString)
            .collectList()
            .flatMap(choices -> {
                if (choices.isEmpty()) return Mono.just(false);
                
                return Flux.fromIterable(choices)
                    .flatMap(id -> redisTemplate.opsForHash().increment(totalsKey, id, -1L))
                    .then(redisTemplate.delete(choicesKey))
                    .then(redisTemplate.opsForSet().remove(votersKey, voterGuid))
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
