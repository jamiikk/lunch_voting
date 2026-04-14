package com.example.lunchvoting.domain;

import java.util.List;

public record Restaurant(String id, String name, String address, Double latitude, Double longitude, List<MenuItem> menu) {}
