---
layout: post
title: "Toss 결제 승인에서 서버가 절대 믿으면 안 되는 값들: paymentKey·orderId·amount"
date: 2026-08-01 15:00:00 +0900
categories: [백엔드, 결제]
tags: [Toss, PG, 검증]
---


> Flown LMS 결제 도메인에서 Toss Payments를 실연동하며 세운 검증 원칙.
> 관련 코드: `TossPaymentClient`(본인 작성, prod 프로필)

## 1. 문제 정의 — "클라이언트가 보낸 금액"을 믿는 순간 뚫린다

결제 승인 흐름에서 가장 기본적이면서 자주 빠지는 구멍은 이거다.

> **결제 승인 요청에 담겨 오는 금액/주문 정보를 서버가 그대로 믿으면, 금액 조작이 가능하다.**

사용자는 10만 원짜리 강의를 결제하면서 승인 요청의 `amount`만 1,000원으로 바꿀 수 있다. 그리고 PG 응답조차 100% 신뢰 대상이 아니다. 그래서 원칙을 이렇게 잡았다.

> **금액과 주문의 진실은 "주문 생성 시 서버가 저장한 값"이다. 클라이언트도, PG 응답도 그 값과 대조해서만 통과시킨다.** (범위: 승인 전·후 검증. 비범위: 결제창 렌더링.)

## 2. 설계 — 요청은 서버 값과, 응답은 요청 값과 대조

**환경 분리**: 운영에서는 `@Profile("prod")`로 `TossPaymentClient`만 활성화하고, secret key와 base-url은 코드가 아니라 설정/환경으로 주입한다. 로컬·테스트는 Mock PG를 쓴다.

**승인 요청**: Toss `/v1/payments/confirm`에 `paymentKey`, `orderId`, `amount`를 보낸다. 이때 `amount`는 클라이언트가 준 값이 아니라 **주문에 저장된 금액**을 기준으로 검증한 뒤 사용한다.

**응답 대조 (핵심)**: Toss confirm 응답은 요청값을 echo한다. 이걸 그냥 성공으로 받지 않고, 응답의 `paymentKey / orderId / totalAmount`를 **요청값과 하나씩 대조**한다. 하나라도 어긋나면 `TossPaymentException`으로 막는다.

```java
if (!confirmedPaymentKey.equals(paymentKey))      throw new TossPaymentException("응답 paymentKey 불일치");
if (!Objects.equals(confirmedOrderId, orderId))   throw new TossPaymentException("응답 orderId 불일치");
if (totalAmount == null || !String.valueOf(totalAmount).equals(String.valueOf(amount)))
    throw new TossPaymentException("응답 금액 불일치");
```

이 검증의 태도는 "PG도 무조건 믿지 않는다"이다. 서버가 알고 있는 주문번호·금액과 맞을 때만 결제를 확정한다.

## 3. 검증

- 정상 승인
- **금액 불일치**: 요청/응답 금액이 다르면 예외로 차단되는지
- **PG 오류/타임아웃**: Mock PG는 기본 5% 확률로 timeout을 시뮬레이션한다. 이때 상위 결제 서비스가 `PG_TIMEOUT`으로 일관 변환하는지

## 4. 트레이드오프

- **외부 API 의존**: PG는 우리 통제 밖이라, 응답 지연·형식 변경에 취약하다. 그래서 응답을 신뢰하지 않고 대조하는 것이다.
- **오류 추상화의 한계**: 현재는 Toss HTTP 오류 전반을 `TossPaymentException` → `PG_TIMEOUT` 단일 코드로 추상화한다. 초기 구현에선 단순함이 이점이지만, 운영 고도화 시 "타임아웃"과 "카드 거절/한도초과" 같은 원인을 구분해야 한다. 이건 의도된 현재 한계다.

## 5. 한계와 다음 단계

- 승인 성공 후 웹훅/결제 조회 API로 상태를 재확인하는 보정은 아직 없다. PG 응답을 못 받은 경우(승인은 됐는데 우리 서버가 응답 유실)의 대사(reconciliation)는 다음 단계다. (이 시나리오의 보정은 "과금 후 미지급" 글과 연결된다.)

## 6. 채용 관점에서 강조하고 싶은 점

결제 승인 검증은 화려한 기능보다 기본 신뢰 경계를 지키는 일이다. 클라이언트가 보낸 금액을 믿지 않고, PG 응답도 서버가 알고 있는 주문 정보와 맞을 때만 통과시키는 원칙을 코드로 고정했다.

개발자 관점에서는 `paymentKey`, `orderId`, `totalAmount` 대조와 운영 프로필 분리, 오류 추상화의 한계가 핵심이다. 인사 관점에서는 사용자가 조작할 수 있는 값을 믿지 않는 태도와, 아직 없는 대사 배치를 다음 단계로 명시하는 정직성이 중요한 신호라고 봤다.

## 7. 배운 것

- **결제에서 신뢰 경계는 "서버가 저장한 값"이다.** 클라이언트 입력도, PG 응답도 그 기준을 통과해야 한다.
- **성공 응답을 성공으로 곧이곧대로 받지 않는 습관**이 결제 도메인의 기본기다.

---

### 면접에서 나올 만한 질문
1. 승인 요청의 `amount`를 클라이언트 값으로 쓰면 왜 위험한가? 서버는 무엇을 진실로 삼아야 하나?
2. PG 응답을 요청값과 대조하는 이유는? 응답을 믿으면 안 되는 시나리오는?
3. PG 오류를 단일 코드(`PG_TIMEOUT`)로 추상화한 트레이드오프는? 언제 세분화해야 하나?
4. secret key/prod 프로필 분리는 어떻게 했나?
5. 승인은 됐는데 응답을 못 받은 경우(응답 유실)는 어떻게 보정하나?

*(`TossPaymentClient` 기반.)*
