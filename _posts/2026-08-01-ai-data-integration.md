---
layout: post
title: "AI라고 부르기 전에: CP-SAT, FSRS, rule-based 기능을 서비스에 붙인 기록"
date: 2026-08-01 21:00:00 +0900
categories: [백엔드, 데이터]
tags: [CP-SAT, FSRS, rule-based, 비동기]
---

Flown LMS에는 개인화 학습 스케줄, 복습일 계산, 이탈위험 점수 같은 기능이 있었다. 겉으로는 AI 기능처럼 보일 수 있지만, 이 글에서는 먼저 선을 긋는다.

> 직접 학습한 예측 모델을 운영한 것이 아니라, CP-SAT 최적화, FSRS 라이브러리, rule-based scoring을 도메인에 맞게 배선한 작업이다.

이 구분은 중요하다. AI라는 말을 붙이면 검증 책임도 같이 커진다. 직접 만들지 않은 알고리즘은 라이브러리 사용이라고 쓰고, 학습된 모델이 아닌 것은 규칙 기반이라고 쓰는 편이 장기적으로 더 신뢰를 만든다.

## CP-SAT 스케줄러

학습 스케줄러의 목표는 단순히 강의를 주차별로 나눠 담는 것이 아니었다. 수능까지 남은 기간 안에 여러 과목의 강의를 배치하되, 특정 주차에 과목이 몰리지 않고, 주차별 학습량이 너무 들쭉날쭉하지 않아야 했다.

단순 CRUD나 균등 분배로 접근하면 조건이 늘어날수록 코드가 예외 처리 덩어리가 된다. 그래서 OR-Tools의 CP-SAT을 사용해 제약을 선언하는 방식으로 풀었다.

스케줄러가 다룬 문제는 대략 이런 형태다.

- 남은 주차 안에 모든 학습 단위를 배치한다.
- 주차별 학습량의 상한과 하한을 둔다.
- 과목이 한쪽으로 몰리지 않게 한다.
- 복습이나 시험 일정처럼 도메인상 중요한 날짜를 고려한다.

이 접근의 장점은 "왜 이 배치가 가능한가"를 제약으로 설명할 수 있다는 점이다. 반대로 제약이 많아질수록 모델링 난도가 올라가고, 결과가 항상 사용자가 기대하는 감각적 균형과 일치하지는 않는다.

스케줄러는 DB나 프레임워크를 직접 import하지 않는 순수 도메인 함수로 뒀다. 외부 데이터는 파라미터로 받고, 결과는 `lesson_id -> week_index` 형태의 값으로 돌려준다.

원본 코드: [Python-Server domain/scheduler.py:17-70](https://github.com/Hard-Click/Python-Server/blob/e24420f4bcf6950ce1aa430e4ce8c18e7b266fc3/domain/scheduler.py#L17-L70)

```python
# domain/scheduler.py
from ortools.sat.python import cp_model

def generate_weekly_schedule(lessons, weekly_caps, prerequisites=None):
    model = cp_model.CpModel()
    num_weeks = len(weekly_caps)

    x = {}
    for lesson in lessons:
        deadline = lesson.get("deadline_week")
        max_week = deadline if deadline is not None else num_weeks - 1
        for w in range(num_weeks):
            if w <= max_week:
                x[(lesson["id"], w)] = model.NewBoolVar(f"x_{lesson['id']}_{w}")
        model.AddExactlyOne(x[(lesson["id"], w)] for w in range(num_weeks) if (lesson["id"], w) in x)

    for w in range(num_weeks):
        terms = [
            lesson["duration_min"] * x[(lesson["id"], w)]
            for lesson in lessons
            if (lesson["id"], w) in x
        ]
        if terms:
            model.Add(sum(terms) <= weekly_caps[w])

    solver = cp_model.CpSolver()
    status = solver.Solve(model)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return None

    return {
        lesson_id: w
        for lesson_id in [l["id"] for l in lessons]
        for w in range(num_weeks)
        if (lesson_id, w) in x and solver.Value(x[(lesson_id, w)]) == 1
    }
```

## FSRS 복습일

복습일 계산은 직접 알고리즘을 만든 것이 아니라 FSRS 라이브러리를 사용했다. 내가 한 일은 퀴즈 점수를 FSRS rating으로 매핑하고, 도메인 흐름에 연결한 것이다.

예를 들어 퀴즈 점수가 높으면 `Easy`나 `Good`, 낮으면 `Hard`나 `Again`으로 매핑해 다음 복습일을 계산한다. 여기서 신경 쓴 부분은 콜드스타트와 상한이다.

개인 리뷰 데이터가 없어도 py-fsrs의 기본 가중치로 바로 동작할 수 있어야 했다. 반대로 고득점을 계속 받은 학생의 복습일이 너무 멀리 밀리면 서비스 맥락에 맞지 않는다. 그래서 복습 간격이 구독 상한이나 수능일을 넘어가지 않도록 캡을 두었다.

FSRS 쪽도 직접 알고리즘을 구현한 것이 아니라, 퀴즈 점수를 `Rating`으로 매핑하고 라이브러리의 `review_card`에 연결했다. 이 부분을 글에 명확히 남기는 이유는 과장하지 않기 위해서다.

원본 코드: [Python-Server domain/review.py:10-43](https://github.com/Hard-Click/Python-Server/blob/e24420f4bcf6950ce1aa430e4ce8c18e7b266fc3/domain/review.py#L10-L43)

연동 코드: [FSRS 테이블 마이그레이션](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/resources/db/migration/V3.1.4__add_fsrs_review_tables.sql#L1-L53), [ReviewCompletionAdapter.java:14-90](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/quiz/infrastructure/review/ReviewCompletionAdapter.java#L14-L90)

```python
# domain/review.py
from fsrs import Scheduler, Card, Rating

def quiz_score_to_grade(score_percent: float) -> Rating:
    if score_percent >= 90:
        return Rating.Easy
    if score_percent >= 70:
        return Rating.Good
    if score_percent >= 50:
        return Rating.Hard
    return Rating.Again

def review_lesson(
    card: Card | None,
    quiz_score_percent: float,
    scheduler: Scheduler | None = None,
    review_datetime=None,
    max_interval_days: int | None = None,
):
    if scheduler is None:
        scheduler = Scheduler(maximum_interval=max_interval_days) if max_interval_days is not None else Scheduler()
    card = card or Card()
    rating = quiz_score_to_grade(quiz_score_percent)
    card, _review_log = scheduler.review_card(card, rating, review_datetime=review_datetime)
    return card, card.due
```

## rule-based 이탈위험

이탈위험 기능은 운영 예측 모델이 아니다. CoxPH 같은 생존분석 모델을 운영 추론에 붙인 것도 아니다. 현재 구현은 rule-based scoring이다.

점수는 세 축을 정규화해 계산했다.

- 최근 미접속일
- 미학습 연속일
- 평균 퀴즈 점수

퀴즈 미응시 학생은 퀴즈 축을 제외하고, 접속과 학습 연속성 중심으로 계산한다. 응시 데이터가 있으면 퀴즈 점수 축을 함께 반영한다. 중요한 것은 총점만 반환하지 않고, 축별 기여도와 `top_reason`을 함께 반환한 점이다.

"위험도 0.72"만 있으면 설명이 어렵다. 하지만 "위험도 0.72이고, 가장 큰 이유는 최근 미접속일"이라고 말할 수 있으면 운영자나 사용자에게 기능을 설명할 수 있다. 이 단계에서는 예측 성능보다 설명 가능성이 더 중요했다.

이탈위험 점수도 총점만 만들지 않고 축별 기여도와 `top_reason`을 함께 반환했다. Java 백엔드는 이 `top_reason` 코드(`recency`, `streak`, `quiz`)를 화면 라벨로 매핑한다.

원본 코드: [Python-Server domain/risk.py:8-77](https://github.com/Hard-Click/Python-Server/blob/e24420f4bcf6950ce1aa430e4ce8c18e7b266fc3/domain/risk.py#L8-L77)

```python
# domain/risk.py
W_RECENCY_3AXIS = 0.45
W_STREAK_3AXIS = 0.30
W_QUIZ_3AXIS = 0.25
W_RECENCY_2AXIS = 0.5
W_STREAK_2AXIS = 0.5

def compute_risk_breakdown(recency_days, miss_streak_days, quiz_avg_score_percent=None):
    recency_score = min(recency_days / 14, 1.0)
    streak_score = min(miss_streak_days / 7, 1.0)

    if quiz_avg_score_percent is None:
        contributions = {
            "recency": round(W_RECENCY_2AXIS * recency_score, 3),
            "streak": round(W_STREAK_2AXIS * streak_score, 3),
        }
        raw_score = W_RECENCY_2AXIS * recency_score + W_STREAK_2AXIS * streak_score
    else:
        quiz_risk_score = min(max(1 - quiz_avg_score_percent / 100, 0.0), 1.0)
        contributions = {
            "recency": round(W_RECENCY_3AXIS * recency_score, 3),
            "streak": round(W_STREAK_3AXIS * streak_score, 3),
            "quiz": round(W_QUIZ_3AXIS * quiz_risk_score, 3),
        }
        raw_score = sum(contributions.values())

    score = round(raw_score, 3)
    top_reason = max(contributions, key=contributions.get)
    return RiskBreakdown(score=score, label=risk_label(score), contributions=contributions, top_reason=top_reason)
```

## 느린 기능을 사용자 요청에 붙이는 법

AI 스케줄 생성은 느리고 실패할 수 있다. CP-SAT 최적화는 수 초가 걸릴 수 있고, Python 서버 호출은 언제든 지연되거나 실패할 수 있다. 이 기능을 수강 신청 요청 안에서 동기로 처리하면, 편의 기능 때문에 핵심 흐름이 느려진다.

그래서 수강 시작 후 `EnrollmentStartedEvent`를 발행하고, 리스너에서 스케줄 생성을 요청했다. 리스너는 `AFTER_COMMIT`에 실행되도록 했다. 이유는 두 가지다.

1. Python 스케줄러가 방금 생성된 수강 등록 데이터를 읽으려면 커밋 이후여야 한다.
2. 스케줄 생성이 실패해도 수강 신청 자체가 롤백되면 안 된다.

또한 전용 executor를 두어 느린 AI 호출이 다른 비동기 작업을 막지 않게 했다. 포화 정책도 기본 `CallerRunsPolicy` 대신 "폐기 + 경고 로그 + 누적 카운트"로 바꿨다. 즉시 생성이 폐기돼도 주간 배치가 미생성 스케줄을 보정하는 구조라면, 사용자 요청 스레드를 붙잡는 것보다 즉시성을 포기하는 편이 낫다고 판단했다.

Java 백엔드에서는 수강 시작 이벤트를 커밋 이후에만 받아 Python 서버를 호출한다. 실패해도 수강 신청 결과를 되돌리지 않고 로그만 남긴다.

원본 코드: [ScheduleGenerationListener.java:20-34](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/enrollment_management/application/listener/ScheduleGenerationListener.java#L20-L34)

```java
// ScheduleGenerationListener.java
@Async("schedulerAiExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onEnrollmentStarted(EnrollmentStartedEvent event) {
    try {
        scheduleGenerationPort.requestGeneration(event.memberId());
    } catch (Exception e) {
        log.error("스케줄 즉시 생성 요청 실패 (memberId={}, courseId={}): {}",
                event.memberId(), event.courseId(), e.getMessage(), e);
    }
}
```

Python 서버 호출 어댑터는 timeout을 명시하고, `schedule.ai.enabled=false`일 때는 즉시 생성을 건너뛴다. 운영에서 기능을 끄더라도 주간 배치가 백업 경로가 된다.

원본 코드: [ScheduleGenerationAiAdapter.java:22-60](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/enrollment_management/infrastructure/ai/ScheduleGenerationAiAdapter.java#L22-L60)
호출 대상: [Python-Server presentation/api.py:83-105](https://github.com/Hard-Click/Python-Server/blob/e24420f4bcf6950ce1aa430e4ce8c18e7b266fc3/presentation/api.py#L83-L105), [GenerateWeeklyScheduleUseCase.execute:99-164](https://github.com/Hard-Click/Python-Server/blob/e24420f4bcf6950ce1aa430e4ce8c18e7b266fc3/application/use_cases.py#L99-L164)

```java
// ScheduleGenerationAiAdapter.java
public void requestGeneration(Long memberId) {
    if (!enabled) {
        log.info("스케줄 즉시 생성 비활성화(schedule.ai.enabled=false) — memberId={} 스킵", memberId);
        return;
    }
    Map<?, ?> body = restClient.post()
            .uri(generatePath)
            .body(Map.of("member_id", memberId))
            .retrieve()
            .body(Map.class);
    log.info("스케줄 즉시 생성 완료 — memberId={}, result={}", memberId, body);
}
```

전용 executor의 포화 정책은 이 글에서 가장 중요한 결정 중 하나였다. `CallerRunsPolicy`를 쓰면 수강 요청 스레드가 긴 Python 호출을 직접 떠안을 수 있기 때문이다.

원본 코드: [AsyncConfig.java:61-80](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/global/config/AsyncConfig.java#L61-L80)

```java
// AsyncConfig.java
@Bean(name = "schedulerAiExecutor")
public Executor schedulerAiExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("SchedulerAi-");

    AtomicLong schedulerAiDiscarded = new AtomicLong();
    executor.setRejectedExecutionHandler((r, e) ->
            log.warn("schedulerAiExecutor 포화 — 즉시 스케줄 생성 작업 폐기(주간 배치가 백업). 누적 폐기 건수={}",
                    schedulerAiDiscarded.incrementAndGet()));
    executor.initialize();
    return executor;
}
```

## 남은 한계

이 기능들이 실제 학습 성과나 이탈률을 개선했다는 A/B 테스트 결과는 없다. FSRS 개인화 optimizer, rule-based 가중치의 실데이터 보정, 즉시 스케줄 생성 실패에 대한 재시도 큐도 다음 단계로 남아 있다.

이 글에서 남기고 싶은 것은 "AI를 만들었다"가 아니다. 느리거나 실패할 수 있는 계산 기능을 핵심 사용자 흐름에 붙일 때, 어디서 실패를 격리하고 무엇을 과장하지 않을지 판단한 과정이다.
