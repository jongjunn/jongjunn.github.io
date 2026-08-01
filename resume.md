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

- 결제 승인 API에서 `Idempotency-Key`, Redis `SETNX` 락, 주문 상태 재조회로 중복 PG confirm 호출을 방어했습니다.
- 동일 멱등키 50개 동시 요청 테스트에서 PG confirm과 DB 결제 완료 처리가 각각 1회만 실행되는지 검증했습니다.
- 환불 가능 여부를 화면 값이 아니라 서버 정책으로 다시 검증하고, 7일 이내와 진도율 10% 미만 기준을 `OrderRefundPolicy`로 분리했습니다.
- 영상 진도 저장에서 `(member_id, video_id)` 유니크 제약과 `REQUIRES_NEW` 복구 흐름으로 중복 row 문제를 다뤘습니다.
- 게시글 목록 조회에서 N+1, 댓글 수 집계, count query 병목을 인덱스, batch IN 조회, `comment_count`, Redis 캐시로 개선했습니다.
- Flyway validate, Swagger API 계약, Sentry 태그, Prometheus/Grafana/Alertmanager 알림 흐름을 정리해 배포 후 원인 추적 기준을 남겼습니다.

## Writing

- [LMS 결제 정합성을 지키는 백엔드 설계]({% post_url 2026-08-01-payment-consistency %})
- [유니크 제약과 멱등 처리]({% post_url 2026-08-01-concurrency-db %})
- [CP-SAT, FSRS, rule-based 기능을 서비스에 붙인 기록]({% post_url 2026-08-01-ai-data-integration %})
- [API, DB, 오류 공유 기준]({% post_url 2026-08-01-ops-collaboration-quality %})
- [게시글 목록 병목 줄이기]({% post_url 2026-08-01-community-performance %})
