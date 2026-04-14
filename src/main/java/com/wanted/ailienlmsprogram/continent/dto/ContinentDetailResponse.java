package com.wanted.ailienlmsprogram.continent.dto;

import com.wanted.ailienlmsprogram.continent.entity.Continent;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ContinentDetailResponse {

    private Continent continent;
    private List<ContinentCourseSummary> courses;
    private List<ContinentPostSummary> posts;
}