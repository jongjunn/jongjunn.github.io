---
layout: post
title: "느린 AI 기능을 사용자 요청에 안전하게 붙이기: 수강 시작 → AI 스케줄 생성 배선"
date: 2026-08-01 09:00:00 +0900
categories: [백엔드, AI]
tags: [비동기, 이벤트, 실패격리]
---


> Flown LMS에서 "수강을 시작하면 AI가 개인 학습 스케줄을 즉시 만들어 캘린더에 올려주는" 기능을, 사용자 요청을 막지 않게 배선한 기록.
> 관련 코드: `ScheduleGenerationListener`, `AsyncConfig(schedulerAiExecutor)`, `ScheduleGenerationAiAdapter` (본인 작성) / 커밋 `a1177869`, `da04c84c`

## 1. 문제 정의 — AI 호출은 느리고, 실패할 수 있고, 몰릴 수 있다

기능 자체는 단순하다. "수강 시작 → AI 스케줄러(Python, CP-SAT)에게 스케줄 생성 요청 → 캘린더 반영." 문제는 그 AI 호출의 **성격**이다.

- **느리다**: CP-SAT 최적화는 수 초가 걸릴 수 있다.
- **실패할 수 있다**: 외부(Python) 서비스라 언제든 죽거나 지연될 수 있다.
- **몰릴 수 있다**: 수강 요청이 몰리면 AI 요청도 같이 몰린다.

그래서 배선의 원칙을 이렇게 잡았다.

> **AI 기능은 편의지 핵심 트랜잭션이 아니다. AI 호출이 느리거나 실패해도, 사용자의 수강 신청 자체는 절대 막히거나 되돌려지면 안 된다.** (범위: AI 스케줄 생성 배선. 비범위: CP-SAT 모델 내부는 별도 글.)

## 2. 설계 — 이벤트 + 커밋 이후 + 비동기

**① 이벤트로 분리.** 수강 시작 로직이 AI 서버를 직접 부르지 않는다. `EnrollmentStartedEvent`를 발행하고, `ScheduleGenerationListener`가 받아 처리한다. 수강 도메인과 AI 도메인을 느슨하게 뗐다.

**② AFTER_COMMIT — 커밋 이후에만.** 리스너를 `@TransactionalEventListener(AFTER_COMMIT)`로 걸었다. 이유가 두 개다.

- Python 스케줄러가 **방금 생긴 enrollment 행을 읽어야** 하므로, 트랜잭션이 커밋된 뒤에 호출해야 한다.
- AI 생성이 실패해도 **수강 신청 저장이 롤백되지 않는다**(이미 커밋됨).

```java
@Async("schedulerAiExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onEnrollmentStarted(EnrollmentStartedEvent event) {
    try {
        scheduleGenerationPort.requestGeneration(event.memberId());
    } catch (Exception e) {
        // 스케줄 생성 실패는 수강신청 결과에 영향을 주지 않는다 — 로깅만 하고 삼킨다.
        log.error("스케줄 즉시 생성 요청 실패 (memberId={}): {}", event.memberId(), e.getMessage(), e);
    }
}
```

**③ 전용 비동기 풀.** `@Async("schedulerAiExecutor")`로 **전용 스레드 풀**에서만 이 느린 작업이 돌게 했다. CP-SAT가 수 초 블로킹돼도 그 블로킹은 이 풀 안에 갇힌다.

## 3. 포화 처리 — 여기가 진짜 결정 (`da04c84c`)

전용 풀이 가득 차면(포화) 어떻게 할지가 핵심이었다. 기본값인 `CallerRunsPolicy`는 **위험**했다.

- CallerRunsPolicy는 풀이 꽉 차면 **호출한 스레드(=수강 요청 스레드)가 그 작업을 대신 실행**한다.
- 그러면 수강 요청 스레드가 최대 수십 초짜리 Python 호출을 **동기로** 떠안는다. AI 편의 기능 때문에 **정작 중요한 수강 요청이 느려지는** 본말전도가 된다.

그래서 포화 정책을 **"폐기 + 경고 로그 + 누적 카운트"** 로 바꿨다. 즉시 생성이 폐기돼도, **주간 배치가 미생성분을 백업 보정**하므로 스케줄은 결국 만들어진다.

```java
// schedulerAiExecutor: core 2 / max 4 / queue 200
executor.setRejectedExecutionHandler((r, e) ->
    log.warn("schedulerAiExecutor 포화 — 즉시 스케줄 생성 작업 폐기(주간 배치가 백업). 누적 폐기 건수={}", ...));
```

## 4. 트레이드오프

- **즉시성 vs 안정성**: 포화 시 "즉시 생성"을 포기(폐기)하는 대신, 사용자 요청 경로의 응답성을 지킨다. 대신 **주간 배치라는 백업 경로**가 있어 스케줄이 유실되지는 않는다. "즉시 아니면 배치로라도" 구조다.
- **강한 결합 vs 최종 일관성**: 이벤트+비동기로 분리하니 수강 즉시 스케줄이 "항상 그 순간" 보장되진 않는다(최종 일관성). 하지만 AI 기능에는 그 정도 지연이 허용된다고 판단했다.

## 5. 한계

- 폐기된 즉시 생성은 주간 배치까지 기다린다. "가능한 빨리 재시도"가 필요하면 재시도 큐가 다음 단계다.
- 실패/폐기율을 운영 규모에서 측정한 데이터는 없다. 누적 폐기 카운트는 관측 훅이지 성과 지표가 아니다.

## 6. 이 사례에서 남긴 것

이 글은 AI 기능 자체보다, 느리고 실패할 수 있는 기능을 핵심 사용자 흐름에 어떻게 붙였는지를 보여준다. `ScheduleGenerationListener`와 `schedulerAiExecutor` 배선은 박종준 본인이 작성한 작업이며, 커밋 `a1177869`의 git blame 병합자 표기와 저자 귀속을 혼동하지 않는다.

인사 관점에서는 "편의 기능 때문에 핵심 수강 신청을 느리게 만들지 않는다"는 사용자 중심 판단과 실패 격리 태도가 중요하다. 개발자 관점에서는 `AFTER_COMMIT`, 전용 executor, 포화 정책 변경, 주간 배치 백업이라는 경계 설계가 코드로 방어되는지가 핵심이다. 운영 규모의 실패율은 측정하지 않았으므로 성과 수치로 말하지 않는다.

## 7. 배운 것

- **AI 기능을 붙일 때 제일 중요한 질문은 "느리거나 실패하면 사용자 핵심 흐름이 어떻게 되나"이다.** 이벤트·AFTER_COMMIT·전용 풀·실패 격리·포화 정책이 전부 그 답이다.
- **기본 정책(CallerRunsPolicy)이 항상 옳지 않다.** 이 경우엔 오히려 사용자 요청을 인질로 잡는 정책이었고, "폐기 + 배치 백업"이 맞았다.

---

### 다시 설명해볼 질문
1. AI 스케줄 생성을 왜 동기 호출이 아니라 이벤트+비동기로 뺐나?
2. `AFTER_COMMIT`을 쓴 두 가지 이유는? (방금 생긴 행 읽기 / 실패 시 롤백 방지)
3. 전용 executor를 따로 둔 이유는? 공용 풀을 쓰면 무슨 문제가 있나?
4. 포화 시 `CallerRunsPolicy`가 왜 위험했나? 대신 어떤 정책을 택했고 왜인가?
5. 즉시 생성이 폐기되면 스케줄은 어떻게 보장되나? (주간 배치 백업)

*(커밋 `a1177869`, `da04c84c` 기반.)*
