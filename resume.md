---
layout: page
title: Resume
permalink: /resume/
---

박종준  
Backend Developer

- Email: <jongjunny2001@gmail.com>
- GitHub: [jongjunn](https://github.com/jongjunn)
- Blog: [jongjunn.github.io](https://jongjunn.github.io)

## Summary

- 지원 포지션: 신입/주니어 백엔드 개발자
- 교육: 원티드 백엔드 개발자 양성과정 (2026.02 ~ 2026.08)
- 대표 프로젝트: Flown LMS (2026.06 ~ 2026.07, 4인 백엔드 팀)
- 대표 코드: [Hard-Click-BackEnd](https://github.com/Hard-Click/Hard-Click-BackEnd) (`develop` 기준 1,049 commits, `jongjunn` 236 commits)
- 기여 확인: [작성 PR 보기](https://github.com/Hard-Click/Hard-Click-BackEnd/pulls?q=is%3Apr+author%3Ajongjunn), [작성 커밋 보기](https://github.com/Hard-Click/Hard-Click-BackEnd/commits/develop/?author=jongjunn)
- 입사 가능 시점: 협의 가능

## Profile

Java/Spring Boot 기반으로 온라인 학습 서비스의 결제, 환불, 수강권 지급, 영상 진도, 학습 스케줄, 커뮤니티 조회 성능을 다뤘습니다.

기능 구현에서 멈추지 않고, 반복 요청이나 외부 API 실패, DB 상태 불일치가 생겼을 때도 서버가 설명 가능한 상태를 유지하는 구조에 관심이 있습니다. 결제 멱등성, 유니크 제약, 트랜잭션 경계, Redis 캐시, 운영 지표처럼 사용자 신뢰와 운영 안정성에 직접 닿는 문제를 중심으로 기록합니다.

## Skills

- Backend: Java, Spring Boot, Spring Data JPA, MySQL
- Data consistency: Transaction, Unique Constraint, Idempotency, Redis Lock
- Performance: Index, N+1 개선, Redis Cache, k6, Datadog
- Operation: Flyway, Swagger/OpenAPI, Sentry, Prometheus, Grafana, Alertmanager
- Python server: FastAPI, OR-Tools, FSRS, rule-based scoring

## Project Experience

### Flown LMS

온라인 강의 수강, 결제, 학습 관리, 복습 스케줄을 다루는 LMS 프로젝트입니다. 백엔드에서 결제 정합성, 학습 데이터 무결성, 커뮤니티 조회 성능, 운영 기준 정리를 맡았습니다.

[대표 코드](https://github.com/Hard-Click/Hard-Click-BackEnd) / [작성 커밋](https://github.com/Hard-Click/Hard-Click-BackEnd/commits/develop/?author=jongjunn) / [작성 PR](https://github.com/Hard-Click/Hard-Click-BackEnd/pulls?q=is%3Apr+author%3Ajongjunn)

[프로젝트 자세히 보기](/project/)

## Writing

- [LMS 결제 정합성을 지키는 백엔드 설계]({% post_url 2026-08-01-payment-consistency %})
- [유니크 제약과 멱등 처리]({% post_url 2026-08-01-concurrency-db %})
- [CP-SAT, FSRS, rule-based 기능을 서비스에 붙인 기록]({% post_url 2026-08-01-ai-data-integration %})
- [API, DB, 오류 공유 기준]({% post_url 2026-08-01-ops-collaboration-quality %})
- [게시글 목록 병목 줄이기]({% post_url 2026-08-01-community-performance %})
