package com.example.lunchvoting.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table("restaurant_locations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantLocation implements Persistable<String> {
    
    @Id
    private String name;
    private Double latitude;
    private Double longitude;

    @Transient
    private boolean isNew = true;

    public RestaurantLocation(String name, Double latitude, Double longitude) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isNew = true;
    }

    @Override
    public String getId() {
        return name;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
