---
layout: page
title: Project
permalink: /project/
---

## Flown LMS

Flown LMS는 온라인 강의 수강, 결제, 학습 관리, 복습 스케줄을 다루는 학습 서비스 프로젝트입니다. 저는 백엔드에서 결제 이후 수강권 지급, 영상 진도 저장, 학습 스케줄 생성, 커뮤니티 조회처럼 사용자가 매일 밟는 흐름이 어긋나지 않도록 API와 운영 기준을 정리했습니다.

대표 코드: [Hard-Click-BackEnd](https://github.com/Hard-Click/Hard-Click-BackEnd)  
기여 기준: `develop` 브랜치 기준 전체 1,049 commits 중 `jongjunn` 236 commits  
기여 확인: [작성 커밋](https://github.com/Hard-Click/Hard-Click-BackEnd/commits/develop/?author=jongjunn), [작성 PR](https://github.com/Hard-Click/Hard-Click-BackEnd/pulls?q=is%3Apr+author%3Ajongjunn)

이 프로젝트에서 가장 많이 고민한 것은 기능을 하나 더 붙이는 일이 아니라, 같은 요청이 반복되거나 외부 시스템이 느려지거나 데이터가 이미 깨져 있어도 서버가 어떤 상태를 진실로 삼을지 정하는 일이었습니다. 그래서 글도 단순 구현 기록보다 문제 정의, 선택 기준, 코드 근거, 검증 결과, 남은 한계를 중심으로 정리했습니다.

## 맡은 영역

- 결제/주문/환불: PG 승인 이후 주문 상태, 수강권 지급, 환불 가능 여부를 서버 정책과 멱등 처리로 관리
- 영상 진도: 영상별 시청 기록 저장, 완료 기준 처리, 중복 진도 데이터 방어
- 학습 스케줄: Python 서버와 연동해 개인별 학습 계획, 복습일, 이탈위험 점수를 생성
- 커뮤니티/조회 성능: 게시글 목록 조회의 N+1, 정렬, count query, Redis 캐시 병목 개선
- 운영 품질: Flyway 마이그레이션, Swagger API 계약, Sentry 오류 추적, Prometheus/Grafana 지표 확인

## 시스템 구성

- Backend: Java, Spring Boot, JPA, MySQL, Redis
- AI Server: Python, FastAPI, OR-Tools, FSRS
- Operation: Flyway, Swagger/OpenAPI, Sentry, Prometheus, Grafana, Alertmanager, Datadog
- Collaboration: GitHub Issue/PR, Notion API 표, Slack 공유 흐름

## 문제별 기록

아래 글들은 "무엇을 구현했는가"보다 "어떤 문제를 맡았고, 왜 그 방식으로 풀었는가"를 기준으로 정리했습니다.

- [과금 이후 권한까지: LMS 결제 정합성을 지키는 백엔드 설계]({% post_url 2026-08-01-payment-consistency %})
- [조회 후 없으면 INSERT는 왜 깨지는가: 유니크 제약과 멱등 처리]({% post_url 2026-08-01-concurrency-db %})
- [AI라고 부르기 전에: CP-SAT, FSRS, rule-based 기능을 서비스에 붙인 기록]({% post_url 2026-08-01-ai-data-integration %})
- [협업을 도구가 아니라 계약으로 만들기: API, DB, 오류 공유 기준]({% post_url 2026-08-01-ops-collaboration-quality %})
- [게시글 목록 병목 줄이기: 인덱스, N+1 제거, COUNT 줄이기]({% post_url 2026-08-01-community-performance %})

## 구현하며 남긴 기준

- 클라이언트 입력보다 서버 주문 상태와 PG 응답 대조를 우선합니다.
- 중복 요청은 Redis 락만이 아니라 DB 상태 재조회와 유니크 제약으로 다시 막습니다.
- 성능 개선은 감으로 판단하지 않고 k6, Grafana, Datadog, 쿼리 수, cache hit/miss를 함께 봅니다.
- 협업 문서는 도구 사용법이 아니라 API 응답, DB 변경, 오류 알림처럼 실제로 깨질 수 있는 계약을 남기는 장소로 둡니다.
