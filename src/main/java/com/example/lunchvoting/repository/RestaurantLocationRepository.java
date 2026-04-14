package com.example.lunchvoting.repository;

import com.example.lunchvoting.domain.RestaurantLocation;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RestaurantLocationRepository extends ReactiveCrudRepository<RestaurantLocation, String> {
}
