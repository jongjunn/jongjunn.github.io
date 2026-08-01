---
layout: page
title: About
permalink: /about/
---

박종준

Java/Spring Boot 기반 백엔드 개발자를 준비하고 있습니다.

Flown LMS 프로젝트에서 온라인 학습 서비스의 결제 승인, 환불 정책, 수강권 지급, 영상 진도 저장, 학습 스케줄 생성, 커뮤니티 조회 성능 개선을 맡았습니다.

기능이 정상 동작하는지에서 멈추지 않고, 반복 요청이나 외부 API 실패, DB 상태 불일치가 생겼을 때도 서버가 설명 가능한 상태를 유지하는 구조에 관심이 있습니다.

## 다루는 문제

- 상태 정합성: 외부 PG 응답, 내부 주문 상태, 사용자 권한이 어긋날 수 있는 흐름을 서버 기준으로 검증합니다.
- 데이터 무결성: 동시 요청으로 생길 수 있는 중복 데이터를 유니크 제약, 트랜잭션, 멱등 처리로 다룹니다.
- 성능 개선: k6, Grafana, Datadog, 쿼리 수, cache hit/miss를 함께 보며 조회 병목을 좁힙니다.
- 운영 가능성: API 계약, DB 마이그레이션, 오류 태그, 알림 기준을 코드와 문서 흐름에 남깁니다.

## 사용 가능한 기술

- Java / Spring Boot: REST API, 인증·인가, 도메인 서비스, 예외 처리
- JPA / MySQL: 엔티티 설계, 트랜잭션, 유니크 제약, 인덱스, 쿼리 튜닝
- Redis: 캐시, 멱등 처리, 중복 요청 방어
- Flyway: 마이그레이션 파일 기반 스키마 변경 관리
- Swagger / OpenAPI: API 명세와 프론트엔드 연동 확인
- Sentry / Prometheus / Grafana / Alertmanager / Datadog: 오류 추적, 지표 확인, 알림 라우팅, 성능 병목 분석
- Python / FastAPI: AI 서버 API, 데이터 처리 흐름 구성
- OR-Tools / FSRS: 학습 스케줄 최적화, 복습 간격 계산

## 프로젝트

- [Flown LMS](/project/): 온라인 학습 서비스 백엔드 구현과 운영 기준 정리

## Links

- GitHub: [jongjunn](https://github.com/jongjunn)
- Email: <jongjunny2001@gmail.com>
