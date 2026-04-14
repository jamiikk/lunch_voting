package com.example.lunchvoting.domain;

import java.time.LocalDateTime;

public record Vote(String restaurantId, String userId, LocalDateTime timestamp) {}
