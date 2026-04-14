package com.wanted.ailienlmsprogram.continent.service;

import com.wanted.ailienlmsprogram.continent.dto.ContinentDetailResponse;
import com.wanted.ailienlmsprogram.continent.entity.Continent;
import com.wanted.ailienlmsprogram.continent.repository.ContinentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContinentService {

    private final ContinentRepository continentRepository;

    public List<Continent> findAllContinents() {
        return continentRepository.findAll();
    }

    public List<Continent> searchContinents(String query) {
        if (query == null || query.trim().isEmpty()) {
            return continentRepository.findAll();
        }

        String keyword = query.trim();

        return continentRepository
                .findByContinentNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByContinentNameAsc(
                        keyword, keyword
                );
    }

    public Continent findById(Long continentId) {
        return continentRepository.findById(continentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 대륙입니다."));
    }

    public ContinentDetailResponse findContinentDetail(Long continentId) {
        Continent continent = findById(continentId);

        return new ContinentDetailResponse(
                continent,
                continentRepository.findPublishedCoursesByContinentId(continentId),
                continentRepository.findTop10PostsByContinentId(continentId)
        );
    }
}