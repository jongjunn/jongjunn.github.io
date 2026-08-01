---
layout: post
title: "조회 후 없으면 INSERT는 왜 깨지는가: 유니크 제약과 멱등 처리"
date: 2026-08-01 22:00:00 +0900
categories: [백엔드, DB]
tags: [동시성, 유니크제약, 격리수준]
---

단일 요청에서 멀쩡한 코드는 동시에 들어오는 요청에서 쉽게 깨진다. Flown LMS에서 영상 진도 저장과 수강 최초 등록을 고치며 이 문제를 직접 만났다. 두 문제의 뿌리는 같았다.

> 조회해서 없으면 INSERT한다.

이 흐름은 한 요청만 보면 자연스럽다. 하지만 두 요청이 거의 동시에 들어오면 둘 다 "없다"고 보고 둘 다 INSERT를 시도할 수 있다. 그래서 데이터 불변식은 애플리케이션 코드만이 아니라 DB 제약으로도 지켜야 한다.

## 영상 진도 중복행

영상 진도 저장의 불변식은 단순했다.

> 한 회원의 한 영상 진도는 DB에 정확히 1행이어야 한다.

즉 `(member_id, video_id)`는 유일해야 한다. 하지만 기존 테이블에는 이 유니크 제약이 없었다. 동시 저장 요청이 들어오면 같은 회원과 영상에 대해 중복행이 생길 수 있었고, 이후 조회 코드가 `Optional<VideoProgress>`를 기대하는 순간 `NonUniqueResultException`으로 터졌다.

문제가 더 나쁜 이유는 일시 오류가 아니라는 점이었다. 중복행이 이미 생긴 회원과 영상 조합은 이후 재생 조회와 진도 저장이 계속 실패했다. 데이터가 깨지면 같은 API를 다시 호출해도 회복되지 않는다.

조치는 세 단계였다.

1. 기존 중복행을 정리한다.
2. `(member_id, video_id)` 유니크 제약을 추가한다.
3. 동시 INSERT 경합에서 유니크 위반이 발생하면, 이미 만들어진 행을 다시 읽어 갱신한다.

여기서 유니크 제약은 단순한 방어선이 아니라 도메인 불변식의 선언이다. 애플리케이션이 실수하거나 여러 요청이 동시에 들어와도 DB가 마지막 경계가 된다.

엔티티에는 이 불변식을 JPA 레벨에서도 드러냈다. 핵심은 `member_id`, `video_id` 조합이 하나만 존재해야 한다는 점이다.

원본 코드: [VideoProgressJpaEntity.java:16-40](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/learning_activity/infrastructure/persistence/VideoProgressJpaEntity.java#L16-L40)

```java
// VideoProgressJpaEntity.java
@Entity
@Table(
        name = "video_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_video_progress_member_video",
                columnNames = {"member_id", "video_id"}
        )
)
public class VideoProgressJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "progress_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;
}
```

이미 깨진 데이터가 있었기 때문에 마이그레이션은 제약 추가만으로 끝나지 않았다. 먼저 중복행을 정리하고, 조합당 가장 많이 진행된 행을 남긴 뒤 제약을 추가했다.

원본 코드: [V3.5.5__dedupe_and_unique_video_progress.sql:1-30](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/resources/db/migration/V3.5.5__dedupe_and_unique_video_progress.sql#L1-L30)

```sql
-- V3.5.5__dedupe_and_unique_video_progress.sql
DELETE FROM video_progress
WHERE progress_id IN (
    SELECT progress_id
    FROM (
        SELECT progress_id,
               ROW_NUMBER() OVER (
                   PARTITION BY member_id, video_id
                   ORDER BY is_completed DESC,
                            watch_time_sec DESC,
                            last_position_sec DESC,
                            progress_id DESC
               ) AS rn
        FROM video_progress
    ) ranked
    WHERE ranked.rn > 1
);

ALTER TABLE video_progress
    ADD CONSTRAINT uk_video_progress_member_video UNIQUE (member_id, video_id),
    ALGORITHM = INPLACE, LOCK = NONE;
```

## REPEATABLE READ와 REQUIRES_NEW

유니크 위반을 잡은 뒤 "먼저 커밋된 행을 읽어 갱신하면 되겠다"고 생각했지만, 여기서 트랜잭션 격리수준을 만났다.

MySQL 기본 격리수준인 `REPEATABLE READ`에서는 트랜잭션의 스냅샷이 첫 조회 시점에 고정된다. 내가 처음 조회했을 때 행이 없었다면, 다른 트랜잭션이 그 뒤에 INSERT 후 커밋해도 같은 트랜잭션 안에서는 그 행이 보이지 않을 수 있다.

그래서 복구 읽기는 `REQUIRES_NEW`로 분리했다. 새 트랜잭션에서 새 스냅샷을 얻어, 방금 커밋된 행을 다시 읽고 업데이트할 수 있게 했다. 격리수준은 시험용 정의가 아니라, 복구 코드에서 실제로 결과를 바꾸는 조건이었다.

코드에서는 INSERT를 별도 트랜잭션으로 분리하고, 유니크 경합에서 밀린 요청이 새 트랜잭션으로 기존 행을 읽어 갱신하게 했다.

원본 코드: [VideoProgressInserter.java:27-58](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/learning_activity/infrastructure/persistence/VideoProgressInserter.java#L27-L58)

```java
// VideoProgressInserter.java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public VideoProgressJpaEntity insert(VideoProgressJpaEntity entity) {
    return repository.saveAndFlush(entity);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public Optional<VideoProgressJpaEntity> updateExisting(
        Long memberId,
        Long videoId,
        Integer lastPositionSec,
        Integer watchTimeSec,
        Boolean completed,
        LocalDateTime completedAt,
        LocalDateTime updatedAt
) {
    return repository.findByMemberIdAndVideoId(memberId, videoId)
            .map(entity -> {
                entity.updateProgress(lastPositionSec, watchTimeSec, completed, completedAt, updatedAt);
                return repository.saveAndFlush(entity);
            });
}
```

호출부는 아무 `DataIntegrityViolationException`이나 복구하지 않는다. 제약 이름을 확인해서 `(member_id, video_id)` 유니크 경합일 때만 멱등 복구로 해석한다. NOT NULL, FK 같은 다른 위반까지 덮어쓰면 진짜 오류를 숨길 수 있기 때문이다.

원본 코드: [VideoProgressRepositoryAdapter.java:21-115](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/domain/learning_activity/infrastructure/persistence/VideoProgressRepositoryAdapter.java#L21-L115)

```java
// VideoProgressRepositoryAdapter.java
private static final String MEMBER_VIDEO_UNIQUE_CONSTRAINT = "uk_video_progress_member_video";

private VideoProgress insert(VideoProgress progress, LocalDateTime now) {
    try {
        return toDomain(inserter.insert(new VideoProgressJpaEntity(
                progress.memberId(),
                progress.courseId(),
                progress.videoId(),
                progress.lastPositionSec(),
                progress.watchTimeSec(),
                progress.completed(),
                progress.completedAt(),
                now
        )));
    } catch (DataIntegrityViolationException violation) {
        if (!isMemberVideoUniqueViolation(violation)) {
            throw violation;
        }

        return inserter.updateExisting(
                        progress.memberId(),
                        progress.videoId(),
                        progress.lastPositionSec(),
                        progress.watchTimeSec(),
                        progress.completed(),
                        progress.completedAt(),
                        now
                )
                .map(this::toDomain)
                .orElseThrow(() -> violation);
    }
}
```

## 수강 최초 등록의 멱등 성공

수강 등록에서도 비슷한 경합이 있었다. 한 사용자가 같은 강의를 최초 등록하는 요청을 거의 동시에 두 번 보낼 수 있다. 불변식은 이렇다.

> `(member_id, course_id)` 수강 등록은 1건이어야 한다.

여기서 유니크 제약 위반을 항상 500으로 반환하는 것은 사용자 경험과 도메인 의미에 모두 맞지 않았다. 두 요청의 목표 상태가 "이미 수강 등록됨"으로 같다면, 두 번째 요청은 실패가 아니라 멱등 성공으로 볼 수 있다.

다만 모든 유니크 위반을 성공으로 바꾸면 안 된다. 멱등 성공으로 해석할 수 있는 기준이 필요하다.

- 최종 상태가 사용자의 의도와 같다.
- 중복 요청으로 추가 부작용이 생기지 않는다.
- 이미 존재하는 행이 같은 사용자와 같은 강의에 대한 것이다.
- 결제나 포인트 차감처럼 외부 부작용이 추가로 발생하지 않는다.

이 기준을 만족할 때만 "이미 처리됨"으로 바꿀 수 있다.

## 락과 제약은 역할이 다르다

동시성 방어에서 락과 유니크 제약은 서로 대체재가 아니다. 락은 동시에 같은 구간에 들어오는 요청을 줄여준다. 유니크 제약은 어떤 경로로 들어오더라도 DB에 깨진 상태가 저장되지 않게 한다.

결제나 환불처럼 외부 PG 호출이 섞인 경로는 Redis 락으로 중복 외부 호출을 줄이고, 주문 상태 재조회로 최종 판단을 했다. 반면 영상 진도나 수강 등록처럼 DB 행의 유일성이 핵심인 경로는 유니크 제약이 반드시 필요했다.

## 이 사례에서 남긴 것

이 작업을 통해 "조회 후 없으면 INSERT"가 왜 위험한지 코드로 이해했다. 동시 요청에서는 조회 결과가 곧 미래의 안전을 보장하지 않는다. 데이터 불변식은 DB 제약으로 선언하고, 제약 위반이 났을 때 에러인지 멱등 성공인지 도메인 기준으로 해석해야 한다.

동시성은 "막았다"고 말하기보다 "어떤 불변식을 어디에서 보장했고, 어떤 테스트로 확인했는가"로 설명해야 한다. 이 관점이 영상 진도, 수강 등록, 결제/환불을 같은 축에서 다시 보게 만들었다.
