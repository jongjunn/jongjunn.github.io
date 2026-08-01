---
layout: post
title: "결제 중복 방어 리팩토링: 현재 주문 결제 경로의 멱등성 설계"
date: 2026-08-01 21:00:00 +0900
categories: [백엔드, 결제]
tags: [멱등성, Redis, SETNX, 동시성]
---


작성일: 2026-07-31

## 먼저 정정한 것

초기 정리에서는 현재 `/api/payments/confirm` 경로와 구형 `PaymentFacade` 경로가 섞여 있었다. 구형 `PaymentFacade`에는 Redis 결과 캐시, DB 멱등키, Redis 락이 함께 있었지만, 현재 주문 결제 경로는 `PaymentConfirmController`에서 `ConfirmOrderPaymentUseCase`로 들어가고 `ConfirmOrderPaymentService`가 처리한다.

따라서 현재 경로를 "Redis 결과 캐시까지 포함한 3중 방어"라고 설명하면 틀리다. 현재 경로의 핵심은 주문 상태를 최종 판단 기준으로 두고, Redis 락과 락 이후 재조회로 PG confirm의 중복 호출을 막는 것이다.

현재 흐름은 다음과 같다.

1. `Idempotency-Key` 헤더의 null, blank, UUID 형식을 검증한다.
2. 주문 상태를 먼저 확인한다.
3. 이미 `READY`가 아니면 PG를 다시 호출하지 않고 duplicate 결과로 반환한다.
4. `order:pay:lock:{Idempotency-Key}` Redis SETNX 락을 잡는다.
5. 락 획득 후 주문 상태를 다시 확인한다.
6. 여전히 `READY`일 때만 PG confirm을 호출한다.
7. Toss 응답의 `paymentKey/orderId/totalAmount`를 요청값과 대조한다.
8. DB 주문 상태를 `PAID`로 바꾸고 수강권 또는 구독권 지급을 시도한다.
9. 락 해제는 Lua 스크립트로 토큰을 비교한 뒤 수행한다.

## 막으려던 실패 시나리오

결제 확정 API에서 가장 위험한 상황은 사용자가 같은 결제를 여러 번 시도하는 것이다. 결제 버튼 연타, 네트워크 timeout 후 재시도, 프론트엔드가 응답을 받지 못해 같은 요청을 다시 보내는 경우가 여기에 해당한다.

이때 목표는 "같은 응답을 예쁘게 돌려준다"가 아니라 "PG confirm이 두 번 호출되어 실제 과금이 중복되지 않게 한다"이다. 현재 구현은 주문 상태를 최종 진실로 두고, Redis 락을 동시 진입 방지 장치로 사용한다.

## 코드 흐름

컨트롤러는 `Idempotency-Key` 헤더를 받고 null, blank, UUID 형식을 검증한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/payment/presentation/PaymentConfirmController.java#L43-L64

서비스는 주문을 조회한 뒤 이미 처리된 주문이면 PG를 다시 호출하지 않는다. 이때 duplicate result로 반환하는 선택은 사용자의 재시도 경험을 망가뜨리지 않으면서, 외부 PG 호출은 막기 위한 선택이다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L75-L84

동시 요청이 거의 같은 순간에 들어오면 둘 다 처음에는 `READY`를 볼 수 있다. 그래서 Redis `SETNX` 락을 잡고, 락 획득 후 주문 상태를 한 번 더 조회한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L87-L99

PG confirm은 락 획득과 재확인 이후에만 호출된다. 성공하면 주문 상태를 `PAID`로 바꾸고 수강권 또는 구독권 지급을 시도한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L101-L116

락 해제는 단순 `DEL`이 아니라 Lua 스크립트로 현재 락 value가 내가 잡은 토큰과 같은지 비교한 뒤 수행한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L43-L47  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L171-L172

## 동시성 테스트로 확인한 것

이번에는 추정이 아니라 테스트로 검증했다.

- 테스트명: `OrderPaymentConcurrencyTest`
- 시나리오: 동일 `Idempotency-Key`로 50개 스레드가 동시에 결제 확정 요청
- 기대값: PG confirm 1회, DB `markPaid` 1회
- 실행 결과: tests=1, failures=0, 2.6s
- 커밋: 원격 push commit `b37aae8c`

테스트 코드:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/test/java/com/wanted/backend/domain/order/application/service/OrderPaymentConcurrencyTest.java

이 테스트가 증명하는 범위는 명확하다. 같은 멱등키를 공유하는 동시 요청에서 현재 서비스 흐름이 PG confirm과 DB 결제 확정 처리를 각각 한 번으로 제한한다는 것이다. 반대로 k6 p95, 처리량, 실서비스 트래픽 안정성은 이 테스트로 증명되지 않는다. 해당 값은 미측정이다.

## 설계상 장점

DB 주문 상태를 최종 진실로 둔 점이 핵심이다. Redis 락은 동시 진입을 줄이는 보조 장치이고, 결제가 이미 확정된 주문인지 판단하는 기준은 주문 상태다.

이 구조에서는 락이 풀린 뒤 같은 요청이 다시 들어오더라도 주문 상태가 `PAID`이므로 PG confirm을 다시 호출하지 않는다. 반대로 거의 동시에 들어온 요청은 Redis 락에서 차단되거나, 락 이후 재조회에서 이미 처리된 주문으로 판단된다.

또한 PG confirm 이후 수강권/구독권 지급을 시도하되, 지급 실패를 결제 rollback과 묶지 않는다. 결제가 이미 외부 PG에서 확정된 뒤라면 주문 상태를 되돌리는 것보다 실패 metric과 error log를 남기고 운영 보정 대상으로 분리하는 편이 더 현실적이라고 판단했다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/ConfirmOrderPaymentService.java#L132-L158

## 트레이드오프

첫째, 현재 경로에는 구형 `PaymentFacade`에 있던 Redis 결과 캐시가 없다. 따라서 같은 멱등키 재요청을 캐시 hit로 빠르게 반환하는 구조가 아니라, 주문 상태 조회를 통해 PG 재호출을 막는 구조에 가깝다. 응답 재현성보다 중복 과금 방어를 우선한 설계다.

둘째, Redis 락 TTL은 30초다. PG confirm 또는 이후 처리가 TTL보다 길어지면 락이 먼저 만료될 수 있다. 그래서 락 자체만 믿지 않고 주문 상태 재조회와 DB 상태 전이를 함께 사용했다. 다만 TTL 초과 상황에서의 장기 지연, 프로세스 장애, 네트워크 단절까지 완전한 운영 보정으로 닫았다고 말할 수는 없다.

셋째, PG confirm 성공 후 DB `markPaid`가 실패하면 외부 결제와 내부 주문 상태가 어긋날 수 있다. 현재 구현은 이 리스크를 인지하고 로그/metric 보정 대상으로 분리했지만, 운영 완성도를 높이려면 결제 보정 테이블, 재처리 worker, 관리자 보정 화면이 필요하다.

## 한계와 다음 개선

- k6 p95/p99, 처리량, 실패율은 이번 테스트에서 측정하지 않았다.
- 실서비스 사용자 규모나 장애 복구 시간도 측정하지 않았다.
- 같은 멱등키의 응답 본문을 완전히 동일하게 재현하는 결과 캐시는 현재 `/confirm` 경로에 없다.
- PG 성공 후 DB 실패를 자동 복구하는 보정 worker는 아직 별도 구현이 필요하다.

## 채용 관점에서 강조하고 싶은 점

이 작업에서 가장 중요하게 둔 태도는 "틀린 설명을 바로잡는 것"이었다. 처음에는 현재 `/confirm` 경로와 구형 `PaymentFacade`의 결과 캐시 구조를 섞어 설명했지만, 코드를 다시 따라가며 현재 경로에 결과 캐시가 없다는 사실을 분리했다. 결제 도메인에서는 그럴듯한 과장보다 정확한 경계가 더 중요하다고 봤다.

팀 관점에서는 재시도 UX, 중복 과금 방어, 보정 가능성을 한 문서 안에서 같이 설명하려 했다. 개발자에게는 Redis 락과 DB 상태 재조회가 어떤 순서로 동작하는지, 인사 담당자에게는 돈이 걸린 기능을 다룰 때 어떤 책임감을 갖고 판단하는지가 읽히도록 정리했다.

## 면접 예상질문

1. Redis 락만으로 중복 결제를 완전히 막을 수 없다고 보는 이유는 무엇인가?
2. 왜 락 획득 후 주문 상태를 다시 조회해야 하는가?
3. 현재 `/confirm` 경로와 구형 `PaymentFacade`의 멱등성 설계 차이는 무엇인가?
4. PG confirm 성공 후 DB `markPaid`가 실패하면 어떤 보정 흐름이 필요한가?
5. 동일 멱등키 50스레드 테스트가 증명하는 것과 증명하지 못하는 것은 무엇인가?
6. 결과 캐시를 추가한다면 어떤 key, TTL, 저장값, 실패 정책을 둘 것인가?
