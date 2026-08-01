---
layout: post
title: "게시글 목록 병목 줄이기: 인덱스, N+1 제거, COUNT 줄이기"
date: 2026-08-01 19:00:00 +0900
categories: [백엔드, 성능]
tags: [N+1, 인덱스, Redis, Grafana]
---

커뮤니티 게시글 목록은 단순 조회처럼 보이지만, 트래픽이 몰리면 서버에서 먼저 티가 난다. Flown LMS에서도 게시글 목록 API를 부하 테스트하면서 DB 커넥션 풀이 꽉 차고, 요청이 30초까지 밀리는 상황을 재현했다.

원인은 하나가 아니었다. 인덱스가 빠진 상태에서 정렬이 느려졌고, 목록을 만든 뒤 작성자 이름과 댓글 수를 다시 조회하면서 N+1이 생겼고, 전체 게시글 수를 매번 세는 count query도 반복됐다.

이 글은 그 병목을 인덱스, 배치 조회, 댓글 수 집계, Redis 캐시로 줄인 과정이다. 숫자는 운영 전체 트래픽이 아니라, 내가 부하 테스트와 모니터링에서 확인한 시나리오 기준으로만 적는다.

## 먼저 본 지표

처음 재현한 상황에서는 게시글 목록 조회가 DB에서 오래 머물렀다. 인덱스가 없는 상태에서는 MySQL이 조건에 맞는 행을 찾고 정렬하기 위해 더 많은 데이터를 훑었고, 느린 쿼리가 DB 커넥션을 오래 붙잡았다. 그 결과 뒤에 들어온 요청도 커넥션을 얻지 못하고 밀렸다.

개선 후 같은 시나리오에서 확인한 값은 이랬다.

| 항목 | 확인한 값 |
|---|---:|
| k6 checks | 100% |
| k6 P95 | 131ms |
| Grafana AVG | 79.7ms |
| Grafana P95 | 128.6ms |
| Error rate | 0% |
| DB pool used | 1 |
| Datadog SQL 수 | 3 queries |
| 게시글 수 캐시 | hit 519 / miss 2 |

중요한 건 "몇 ms 빨라졌다"보다 병목의 위치가 바뀌었다는 점이었다. DB 커넥션을 오래 잡고 있던 요청이 줄어들자, 뒤 요청이 줄줄이 밀리는 현상도 같이 사라졌다.

## 실험은 한 번에 끝나지 않았다

처음부터 최종 구조를 바로 고른 것은 아니었다. 게시글 목록도 정렬 조건에 따라 성격이 달랐다. 최신순 목록은 count query와 반복 조회를 줄이면 충분히 빨라졌지만, 댓글순 목록은 단일 JOIN으로 줄여도 `COUNT + GROUP BY + ORDER BY` 비용이 남았다.

내가 따로 정리해둔 측정 캡처를 다시 보면 차이가 더 분명했다.

| 시나리오 | 관찰한 값 | 해석 |
|---|---:|---|
| 최신순 목록 after | k6 P95 32ms / 120.1 req/s / error 0% | 기본 목록은 캐시와 배치 조회만으로도 충분히 안정적이었다. |
| 최신순 목록 Grafana | AVG 22.4ms / P95 33.5ms / cache hit 99.8% | count cache가 실제로 hit 되는지 지표로 확인했다. |
| 댓글순 목록 JOIN 초기 | P95 8.579s / 10.8 req/s | 쿼리 수를 줄여도 댓글수 정렬 비용이 남았다. |
| 댓글순 목록 JOIN 재측정 | P95 7.8s / 11.4 req/s / error 0% | 실패는 없지만 SLO에는 못 들어왔다. |
| 댓글순 목록 Grafana | AVG 6.7s / P95 9.8s / DB pool 10 | 느린 쿼리가 커넥션을 오래 붙잡았다. |
| Datadog JOIN trace | 요청 526ms 중 JOIN 쿼리 508ms | 병목이 애플리케이션보다 집계 쿼리에 가까웠다. |

이 결과 때문에 결론이 바뀌었다. "N+1을 없앴다"나 "JOIN으로 합쳤다"에서 끝내면 부족했다. 최신순 목록과 댓글순 목록을 같은 문제로 보면 안 됐고, 댓글순 정렬은 결국 댓글 수를 매번 계산하지 않는 구조까지 가야 했다.

## 인덱스는 조회 조건과 정렬 기준에 맞춰야 한다

게시글 목록은 보통 게시판 종류, 공개 상태, 생성일 기준으로 조회된다. 그래서 `posts` 테이블에는 목록 조회에 맞춘 복합 인덱스를 뒀다.

```java
// PostJpaEntity.java
@Table(
        name = "posts",
        indexes = {
                @Index(
                        name = "idx_posts_board_status_created",
                        columnList = "board_type,status,created_at"
                ),
                @Index(
                        name = "idx_posts_board_status_count",
                        columnList = "board_type,status,comment_count"
                )
        }
)
public class PostJpaEntity {
    @Column(name = "comment_count", nullable = false)
    private Integer commentCount = 0;
}
```

여기서 인덱스를 단순히 "많이 달았다"가 아니라, 실제 조회 패턴에 맞췄다. 최신순 목록은 `board_type`, `status`, `created_at` 조합을 타고, 댓글순 목록은 `board_type`, `status`, `comment_count` 조합을 타게 했다.

인덱스가 빠진 상태에서는 DB가 필요한 행을 빨리 좁히지 못하고 정렬 비용까지 떠안았다. 조회 API의 병목이 애플리케이션 로직처럼 보여도, 첫 번째 확인 지점은 실행 계획과 인덱스였다.

## 목록 조회에서 N+1을 없앴다

다음 문제는 목록을 가져온 뒤의 추가 조회였다. 게시글 20개를 조회한 다음 작성자 이름, 댓글 수를 각각 다시 가져오면 요청 하나가 여러 쿼리로 쪼개진다. 데이터가 적을 때는 티가 안 나지만, 부하 테스트에서는 이 차이가 바로 커넥션 사용량으로 드러난다.

그래서 게시글 목록을 먼저 가져오고, 필요한 작성자 id와 게시글 id를 모아 한 번씩 배치 조회했다.

```java
// PostQueryService.java
private PageResult<PostListItem> getListByBatchIn(
        BoardType boardType,
        int page,
        int size,
        String keyword
) {
    Page<Post> postPage = postReaderPort.getList(boardType, page, size, keyword);
    List<Post> posts = postPage.getContent();

    List<Long> authorIds = posts.stream()
            .map(Post::getAuthorId)
            .distinct()
            .toList();

    List<Long> postIds = posts.stream()
            .map(Post::getId)
            .toList();

    Map<Long, String> authorNames = memberNamePort.getNamesByMemberIds(authorIds);
    Map<Long, Long> commentCounts = commentRepository.countsByPostIds(postIds);

    List<PostListItem> items = posts.stream()
            .map(post -> toListItem(post, authorNames, commentCounts))
            .toList();

    return PageResult.of(items, postPage.getNumber(), postPage.getSize(), postPage.getTotalElements());
}
```

댓글 수는 게시글 id 목록을 `IN` 조건으로 넘겨 한 번에 묶었다.

```java
// SpringDataCommentRepository.java
@Query("""
        SELECT c.postId AS postId, COUNT(c) AS cnt
        FROM CommentJpaEntity c
        WHERE c.postId IN :postIds
        GROUP BY c.postId
        """)
List<CommentCountRow> countByPostIdIn(@Param("postIds") Collection<Long> postIds);
```

이렇게 바꾸면 게시글 개수가 늘어나도 작성자 조회와 댓글 수 조회가 게시글 수만큼 늘지 않는다. 목록 API에서 가장 먼저 잡아야 할 것은 "한 화면을 만들기 위해 쿼리가 몇 번 나가는가"였다.

비슷한 문제가 댓글 상세 조회에서도 있었다. 댓글이 많은 게시글 하나를 조회할 때 작성자명과 대댓글을 개별로 다시 가져오면, 목록보다 더 눈에 띄게 느려졌다. 별도 k6 측정에서는 댓글 상세 조회가 before 기준 P50 8.0s, P95 10.1s였고, 작성자명과 대댓글을 `IN` 조회로 묶은 뒤에는 P50 0.2s, P95 0.4s까지 내려왔다.

여기서 얻은 기준은 단순했다. 화면 하나를 만들 때 반복되는 조회가 보이면 먼저 id를 모으고, DB에는 묶어서 물어봐야 한다.

## 댓글순 정렬은 매번 COUNT하지 않게 했다

댓글순 목록은 더 까다로웠다. 단순히 `comments`를 `LEFT JOIN`하고 `COUNT(c.id)`로 정렬하면 정확하긴 하지만, 게시글과 댓글이 늘어날수록 정렬 비용이 커진다.

처음에는 JOIN + DTO projection 방식으로 필요한 필드만 조회했다. 이 방식은 쿼리 수를 줄이는 데는 효과가 있었다. 하지만 부하 테스트에서는 댓글순 정렬이 여전히 P95 7~9초대로 남았다. Datadog에서도 요청 526ms 중 JOIN 쿼리만 508ms를 차지하는 trace가 보였다. 즉 병목은 "쿼리 개수"에서 "집계와 정렬 비용"으로 옮겨간 상태였다.

```java
// PostRepositoryAdapter.java
@Query("""
        SELECT new com.wanted.backend.domain.community.application.dto.PostSummaryDto(
            p.id, p.authorId, m.nickname, p.boardType, p.title,
            p.content, p.status, p.createdAt, p.updatedAt, COUNT(c.id)
        )
        FROM PostJpaEntity p
        JOIN MemberReferenceEntity m ON m.memberId = p.authorId
        LEFT JOIN CommentJpaEntity c ON c.postId = p.id
        WHERE p.boardType = :boardType AND p.status = :status
        GROUP BY p.id, p.authorId, m.nickname, p.boardType, p.title,
                 p.content, p.status, p.createdAt, p.updatedAt
        ORDER BY COUNT(c.id) DESC, p.createdAt DESC
        """)
Page<PostSummaryDto> findSummaryByBoardTypeOrderByCommentCount(...);
```

이후에는 댓글 수를 게시글에 `comment_count`로 들고 가는 방식까지 적용했다. 댓글 생성/삭제 시 게시글의 댓글 수를 같이 갱신하고, 목록에서는 그 값을 기준으로 정렬한다.

```java
// SpringDataPostRepository.java
@Modifying(clearAutomatically = true)
@Query("""
        UPDATE PostJpaEntity p
        SET p.commentCount = p.commentCount + 1
        WHERE p.id = :postId
        """)
void incrementCommentCount(@Param("postId") Long postId);

@Modifying(clearAutomatically = true)
@Query("""
        UPDATE PostJpaEntity p
        SET p.commentCount = p.commentCount - 1
        WHERE p.id = :postId AND p.commentCount > 0
        """)
void decrementCommentCount(@Param("postId") Long postId);
```

댓글 수를 비정규화하면 읽기는 가벼워지지만, 쓰기 쪽 책임이 생긴다. 댓글 생성과 삭제가 실패했을 때 `comment_count`가 어긋나지 않게 같은 트랜잭션 안에서 다루어야 한다. 그래서 이 방식은 "무조건 좋다"가 아니라, 읽기 빈도가 높고 정렬 비용이 큰 목록에서 선택한 절충안에 가깝다.

## 전체 count query는 캐시했다

마지막으로 반복 호출되는 전체 게시글 수 조회를 Redis 캐시에 올렸다. 여기서는 검색어가 없는 기본 목록만 캐시하고, 검색어가 있는 경우는 캐시하지 않았다. 키 종류가 너무 늘어나면 캐시 관리 비용이 더 커질 수 있기 때문이다.

```java
// PostCountCache.java
public Long count(BoardType boardType, String keyword) {
    if (hasText(keyword)) {
        return repository.count(boardType, keyword);
    }

    Cache.ValueWrapper cached = cache.get(cacheKey(boardType));
    if (cached != null && cached.get() instanceof Long value) {
        hitCounter.increment();
        return value;
    }

    missCounter.increment();
    Long count = repository.count(boardType, null);
    cache.put(cacheKey(boardType), count);
    return count;
}
```

캐시는 붙이는 것보다 확인이 더 중요했다. 그래서 hit/miss를 지표로 남겼고, 테스트 중 `post_count_cache hit 519, miss 2`까지 확인했다. 캐시가 실제로 읽히는지 보지 않으면, 코드는 바뀌었는데 성능은 그대로인 상태를 놓칠 수 있다.

## 남은 기준

이 개선에서 제일 크게 배운 것은 성능 문제를 감으로 고치면 안 된다는 점이다. 처음에는 "JPA가 느린가", "서버가 부족한가"처럼 보였지만, 지표를 보면 병목은 더 구체적이었다.

- 인덱스가 없어서 목록 조회가 오래 걸렸다.
- 목록 하나를 만들기 위해 추가 쿼리가 반복됐다.
- 댓글순 정렬에서 매번 댓글 테이블을 집계했다.
- 전체 count query가 같은 조건으로 계속 호출됐다.

그래서 개선도 각각의 병목에 맞게 나누었다. 인덱스로 조회 범위를 줄이고, 배치 조회로 N+1을 없애고, 댓글 수를 비정규화해 정렬 비용을 줄이고, 반복 count query는 캐시했다.

아직 남은 한계도 있다. 검색어별 count는 캐시하지 않았고, `comment_count`는 댓글 생성/삭제 흐름이 어긋나면 값이 틀어질 수 있다. 성능 수치도 내가 재현한 부하 테스트 기준이므로 모든 운영 상황을 대표하지 않는다.

다만 이 작업 이후에는 게시글 목록 성능을 설명할 때 "빨라졌다"가 아니라, 어떤 쿼리가 줄었고 어떤 지표가 바뀌었는지 말할 수 있게 됐다. 취업 준비용 글로도 이 차이가 중요하다고 생각한다.
