package com.wanted.ailienlmsprogram.continent.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "CONTINENT")
@Getter
@Setter
@NoArgsConstructor
public class Continent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "continent_id")
    private Long continentId;

    @Column(name = "continent_name", nullable = false, unique = true, length = 100)
    private String continentName;

    @Column(name = "continent_description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "continent_thumbnail_url", length = 500)
    private String thumbnailUrl;
}