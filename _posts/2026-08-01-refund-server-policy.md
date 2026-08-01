---
layout: post
title: "환불은 프론트 버튼이 아니라 서버 정책이다: 7일/10%/부분환불 설계"
date: 2026-08-01 19:00:00 +0900
categories: [백엔드, 결제]
tags: [환불, 서버정책, 동시성]
---


작성일: 2026-07-31

## 문제의 출발점

환불 기능에서 프론트엔드가 `refundable=true`를 보여주는 것은 사용자 경험을 위한 표시일 뿐이다. 사용자는 화면을 오래 열어둘 수 있고, 요청 payload를 바꿀 수도 있으며, 버튼이 보이지 않아도 API를 직접 호출할 수 있다.

그래서 환불 가능 여부는 반드시 서버가 환불 실행 시점에 다시 판단해야 한다. 이 프로젝트의 강의 환불 조건은 두 가지다.

1. 결제 후 7일 이내
2. 진도율 10% 미만

여기서 중요한 점은 `10% 이하`가 아니라 `10% 미만`이라는 것이다. 코드도 `progressPercent < 10`으로 판단한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/domain/policy/OrderRefundPolicy.java#L14-L33

## 정책 숫자를 별도 객체로 분리한 이유

환불 조건은 조회 화면과 실행 API에서 모두 쓰인다. 조회 화면에서는 사용자가 환불 가능 여부를 미리 볼 수 있어야 하고, 실행 API에서는 같은 조건을 다시 강제해야 한다.

이 조건이 컨트롤러나 서비스에 흩어지면 조회와 실행의 판단이 달라질 수 있다. 그래서 `OrderRefundPolicy`를 단일 진실 소스로 두고, 기간과 진도율 판단을 같은 메서드로 공유했다.

이 선택은 단순히 코드 정리를 위한 리팩토링이 아니다. 환불은 금전 상태를 바꾸는 기능이기 때문에 "화면에는 가능이라고 보였지만 서버에서는 불가능" 또는 "화면에는 불가능이라고 보였지만 API로는 가능" 같은 불일치가 곧 운영 리스크가 된다.

## 실행 시점에 다시 검증하는 흐름

강의 환불 서비스는 먼저 같은 주문 항목에 대한 동시 환불을 막기 위해 `order:refund:lock:{orderId}:{courseId}` 형태의 Redis 락을 사용한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundOrderItemService.java#L37-L52

락 안에서는 주문 소유자, 주문 상태, 주문 항목 존재 여부, 이미 환불된 항목인지 여부를 다시 검증한다. 이미 환불된 항목이면 조용히 반환한다. 같은 요청이 늦게 들어와도 PG cancel을 다시 호출하지 않기 위한 선택이다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundOrderItemService.java#L53-L70

그 다음 서버 현재 시각 기준으로 환불 기간을 확인하고, 현재 진도율을 조회해 10% 미만인지 다시 판단한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundOrderItemService.java#L73-L83

## 부분환불과 주문 상태 전이

강의 여러 개가 한 주문에 묶여 있을 수 있기 때문에 환불은 주문 전체가 아니라 주문 항목 단위로 처리된다. 환불 후 다른 미환불 항목이 남아 있으면 주문 상태는 `PARTIAL_REFUNDED`, 모든 항목이 환불되면 `REFUNDED`가 된다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundOrderItemService.java#L86-L100

DB 상태 전이는 repository의 트랜잭션 메서드에서 수행된다. 이 메서드는 주문 상태를 바꾸고, 대상 주문 항목을 환불 처리한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/infrastructure/persistence/OrderRepositoryAdapter.java#L73-L83

구독 환불은 강의 항목 환불과 다르게 주문 단위로 처리한다. 구독 환불 금액은 `min(연간권가, 결제액)`으로 제한해 결제 원금을 넘지 않게 한다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundSubscriptionService.java#L53-L58

## Toss cancel 연동과 보정 리스크

운영 프로필에서는 Toss cancel API로 실제 PG 취소 요청을 보낸다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/payment/infrastructure/pg/TossPaymentClient.java#L87-L100

현재 구현은 PG cancel을 먼저 호출하고, 성공하면 DB 상태를 갱신한다. 이 구조는 DB 커넥션을 PG 호출 동안 잡지 않는 장점이 있지만, PG cancel 성공 후 DB 갱신이 실패하면 운영 보정이 필요하다.

또 DB 환불 상태 갱신 후 수강권 회수에 실패해도 환불 자체를 rollback하지 않고 error log를 남긴다.

코드 출처:  
https://github.com/Hard-Click/Hard-Click-BackEnd/blob/b37aae8cbca2e941f615827d4c5a6cd10df2d430/src/main/java/com/wanted/backend/domain/order/application/service/RefundOrderItemService.java#L99-L104

따라서 이 구현은 "완성된 정산 시스템"이라고 말하기보다, "서버 정책 강제와 상태 전이는 구현했고, PG와 DB 사이 보정 리스크를 인지하고 있다"고 설명하는 편이 정확하다.

## 동시성 테스트로 확인한 것

이번에는 동일 주문 항목에 대해 50개 스레드가 동시에 환불을 요청하는 테스트를 작성하고 실행했다.

- 테스트명: `OrderRefundConcurrencyTest`
- 시나리오: 동일 주문 항목 50스레드 동시 환불 요청
- 기대값: PG cancel 1회, DB `refundItem` 1회
- 실행 결과: tests=1, failures=0
- 커밋: 로컬 커밋 `ffe8ef5d`

테스트 코드 경로:  
`src/test/java/com/wanted/backend/domain/order/application/service/OrderRefundConcurrencyTest.java`

이 테스트가 증명하는 범위는 동일 주문 항목에 대한 동시 환불 진입에서 외부 PG 취소와 DB 환불 처리가 한 번으로 제한된다는 것이다. 반대로 실서비스 트래픽에서의 p95, 처리량, 장애 복구 시간은 이 테스트로 증명되지 않는다. 해당 값은 미측정이다.

## 트레이드오프

첫째, PG cancel을 DB 트랜잭션 안에 넣지 않았다. 외부 API 호출 동안 DB 커넥션을 오래 잡지 않는 장점이 있지만, PG 성공 후 DB 실패가 발생하면 보정이 필요하다.

둘째, 이미 환불된 항목에 대한 재요청은 PG cancel을 다시 호출하지 않고 반환한다. 사용자 재시도와 네트워크 중복 요청에는 유리하지만, 응답 본문을 이전 요청과 완전히 동일하게 재현하는 결과 캐시 구조는 아니다.

셋째, Redis 락은 동시 진입을 줄이지만 영구적 진실은 아니다. 그래서 주문 항목의 환불 상태를 다시 확인한다. 다만 프로세스 장애, Redis 장애, PG 성공 후 DB 실패까지 자동으로 닫는 운영 보정 worker는 아직 별도 개선 과제다.

## 한계와 다음 개선

- k6 p95/p99, 처리량, 실서비스 사용자 규모는 측정하지 않았다.
- 환불 보정 테이블과 재처리 worker는 아직 구현되지 않았다.
- PG cancel 성공 후 DB 실패를 운영자가 추적할 수 있는 관리자 보정 화면은 필요하다.
- 주문 항목 환불과 구독 환불은 정책과 금액 산정 기준이 다르므로 문서와 테스트를 계속 분리해야 한다.

## 채용 관점에서 강조하고 싶은 점

환불은 "버튼을 보이게 할지"가 아니라 회사와 사용자 사이의 신뢰 문제라고 봤다. 그래서 프론트 표시를 편의 기능으로만 두고, 실행 시점의 서버 정책을 최종 기준으로 삼았다. 이 판단은 협업에서도 중요하다. 프론트엔드가 어떤 화면을 만들든 백엔드는 같은 정책을 강제하므로, 팀 안에서 책임 경계가 선명해진다.

기술적으로는 PG cancel을 DB 트랜잭션 밖에 둔 이유와 그 대가를 함께 적었다. 인사 관점에서는 정책을 일관되게 지키려는 태도, 개발자 관점에서는 락·상태 재확인·부분환불 상태 전이·미구현 보정 범위를 코드 근거로 설명할 수 있는 점을 핵심 신호로 남겼다.

## 면접 예상질문

1. 왜 프론트엔드의 `refundable` 표시만으로 환불 권한을 판단하면 안 되는가?
2. `10% 이하`가 아니라 `10% 미만`으로 정책을 둔 이유와 코드 근거는 무엇인가?
3. PG cancel을 DB 트랜잭션 밖에서 호출하는 장단점은 무엇인가?
4. PG cancel 성공 후 DB 상태 갱신이 실패하면 어떤 보정 테이블이나 재처리 흐름이 필요한가?
5. 동일 주문 항목 50스레드 환불 테스트가 증명하는 것과 증명하지 못하는 것은 무엇인가?
6. 구독 환불 금액을 `min(연간권가, 결제액)`으로 제한한 이유는 무엇인가?
