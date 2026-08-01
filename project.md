---
layout: page
title: Project
permalink: /project/
---

## Flown LMS

Flown LMS는 온라인 강의 수강, 결제, 학습 관리, 복습 스케줄을 다루는 학습 서비스 프로젝트입니다. 저는 백엔드 영역에서 사용자가 결제하고, 강의를 듣고, 학습 데이터를 쌓는 흐름이 끊기지 않도록 API와 운영 흐름을 정리했습니다.

## 맡은 영역

- 결제/주문/환불: PG 승인 이후 주문 상태, 수강권 지급, 환불 가능 여부를 서버 정책으로 관리
- 영상 진도: 영상별 시청 기록 저장, 완료 기준 처리, 중복 진도 데이터 방어
- 학습 스케줄: Python AI 서버와 연동해 개인별 학습 계획과 복습 일정을 생성
- 커뮤니티/조회 성능: 게시글 목록 조회의 N+1, 정렬, count query, 캐시 병목 개선
- 운영 품질: Flyway 마이그레이션, Swagger API 문서, Sentry 오류 추적, 성능 지표 확인

## 시스템 구성

- Backend: Java, Spring Boot, JPA, MySQL, Redis
- AI Server: Python, FastAPI, OR-Tools, FSRS
- Operation: Flyway, Swagger/OpenAPI, Sentry, Grafana, Datadog
- Collaboration: GitHub Issue/PR, Notion API 표, Slack 공유 흐름

## 블로그에서 정리한 문제

- [결제 중복 방어 리팩토링: 현재 주문 결제 경로의 멱등성 설계]({% post_url 2026-08-01-payment-consistency %})
- [조회 후 없으면 INSERT는 왜 깨지는가: 유니크 제약과 멱등 처리]({% post_url 2026-08-01-concurrency-db %})
- [CRUD가 아닌 백엔드: 학습 스케줄을 CP-SAT 제약 최적화로 짠 이유]({% post_url 2026-08-01-ai-data-integration %})
- [협업을 도구가 아니라 계약으로 만들기: API, DB, 오류 공유 기준]({% post_url 2026-08-01-ops-collaboration-quality %})
- [게시글 목록 병목 줄이기: 인덱스, N+1 제거, COUNT 줄이기]({% post_url 2026-08-01-community-performance %})
