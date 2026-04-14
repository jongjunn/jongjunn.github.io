package com.wanted.ailienlmsprogram.continent.repository;

import com.wanted.ailienlmsprogram.continent.dto.ContinentCourseSummary;
import com.wanted.ailienlmsprogram.continent.dto.ContinentPostSummary;
import com.wanted.ailienlmsprogram.continent.entity.Continent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContinentRepository extends JpaRepository<Continent, Long> {

    List<Continent> findByContinentNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByContinentNameAsc(
            String continentNameKeyword,
            String descriptionKeyword
    );

    @Query(value = """
            SELECT
                c.course_id AS courseId,
                c.title AS courseTitle,
                c.price AS price,
                m.name AS instructorName
            FROM COURSE c
            JOIN MEMBER m ON c.instructor_id = m.member_id
            WHERE c.continent_id = :continentId
              AND c.status = 'PUBLISHED'
            ORDER BY c.created_at DESC, c.course_id DESC
            """, nativeQuery = true)
    List<ContinentCourseSummary> findPublishedCoursesByContinentId(@Param("continentId") Long continentId);

    @Query(value = """
            SELECT
                p.post_id AS postId,
                p.title AS title,
                m.name AS writerName,
                p.is_notice AS notice,
                p.created_at AS createdAt
            FROM CONTINENT_POST p
            JOIN MEMBER m ON p.author_id = m.member_id
            WHERE p.continent_id = :continentId
              AND p.is_deleted = false
            ORDER BY p.is_notice DESC, p.created_at DESC, p.post_id DESC
            LIMIT 10
            """, nativeQuery = true)
    List<ContinentPostSummary> findTop10PostsByContinentId(@Param("continentId") Long continentId);
}