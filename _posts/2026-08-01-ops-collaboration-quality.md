---
layout: post
title: "협업을 도구가 아니라 계약으로 만들기: API, DB, 오류 공유 기준"
date: 2026-08-01 20:00:00 +0900
categories: [협업, 운영]
tags: [API계약, SLO, Flyway, Alertmanager, Sentry]
---

협업은 회의를 많이 하거나 도구를 많이 쓰는 것으로 좋아지지 않았다. Flown LMS를 만들며 실제로 막혔던 지점은 더 단순했다. 프론트엔드는 어떤 요청과 응답을 믿고 개발해야 하는지, 백엔드는 어떤 DB 변경이 배포를 막을 수 있는지, 장애가 났을 때 누가 어떤 정보를 보고 움직여야 하는지가 불명확했다.

그래서 이 글은 "협업 도구를 정리했다"는 이야기가 아니다. 팀원이 같은 상태를 보게 만들기 위해 API 계약, 스키마 변경, 오류 알림을 어디에 남기고 어떻게 검증했는지에 대한 기록이다.

## 내가 실제로 맡은 기준

이 프로젝트에서 협업을 정리한다는 말은 회의록을 예쁘게 만드는 일이 아니었다. 실제로는 프론트엔드와 백엔드가 서로 다른 상태를 믿고 개발하거나, DB 변경 하나로 배포가 막히거나, 500 오류가 났는데 담당 도메인을 바로 좁히지 못하는 문제를 줄이는 일이었다.

그래서 내가 잡은 기준은 아래 네 가지였다.

- API 변경은 PR에서 요청, 응답, 예외, 프론트 주의사항이 먼저 보여야 한다.
- DB 변경은 Flyway 마이그레이션과 Hibernate validate 부팅으로 검증되어야 한다.
- 운영 오류는 Sentry 태그와 Slack 알림만 보고도 도메인과 URL을 좁힐 수 있어야 한다.
- 결제처럼 사용자 신뢰가 바로 깨지는 흐름은 평균 응답시간보다 중복 결제, 성공률, P95처럼 실패 조건을 분리해 봐야 한다.

이 기준이 있어야 Notion, Swagger, Postman, Slack이 도구 나열로 끝나지 않는다. 각각의 도구가 어느 변경 지점에서 어떤 사고를 막는지 설명할 수 있어야 협업 기록으로 남길 수 있었다.

## 협업 문제를 다시 정의하기

초기에는 Notion, Swagger, Postman, Slack, PR 설명이 모두 따로 움직였다. 각각은 필요했지만, 변경 사항이 여러 곳에 흩어지면 다음 문제가 생긴다.

- 프론트엔드는 문서를 보고 호출했는데 실제 엔드포인트가 다르다.
- 응답 필드나 enum이 바뀌었는데 화면 작업자가 늦게 알게 된다.
- DB 마이그레이션 파일은 머지됐지만 운영 서버가 부팅 단계에서 멈춘다.
- 500 오류가 발생해도 어느 도메인 담당자가 봐야 하는지 알림만 보고 판단하기 어렵다.

이 문제를 "소통이 부족했다"로만 정리하면 해결이 흐려진다. 내가 잡은 기준은 이것이었다.

> 협업 정보는 말로 전달하는 것이 아니라, 변경이 발생하는 지점에 남아야 한다.

이 기준을 세우고 나서 팀 문서를 다시 보면, 쓸 만한 기록과 그렇지 않은 기록이 갈렸다. 단순 일정표나 역할표보다 도움이 된 것은 실제 연동이 깨졌던 지점, API 표의 운영 메모, DB 변경 규칙, GitHub Issue/PR 처리 흐름이었다.

## SLO는 사용자 기대에서 시작했다

운영 기준을 잡을 때도 응답시간부터 정하지 않았다. 먼저 사용자가 어떤 순간에 신뢰를 잃는지 정의했다. 그 다음 실패 조건, 측정 지표, 목표 수치, 알림 조건을 붙였다.

```text
사용자 기대
-> 실패 조건
-> SLI
-> SLO
-> 에러 버짓
-> 알림 조건
```

내가 맡은 결제 도메인은 이 방식이 특히 잘 맞았다. 결제는 평균 응답시간보다 "돈이 두 번 빠져나가지 않는다"가 먼저다. 그래서 중복 결제는 7일 기준 0건으로 두고, 결제 성공률과 P95 응답시간은 별도 지표로 분리했다.

| 사용자 기대 | 실패 조건 | SLO |
|---|---|---|
| 결제 버튼을 여러 번 눌러도 돈은 한 번만 빠진다 | 동일 멱등키로 2건 이상 결제 저장 | 중복 결제 0건 / 7일 |
| 결제를 시도하면 정상 처리된다 | 결제 요청 중 실패 비율 초과 | 성공률 99.5% 이상 |
| 결제 결과가 너무 늦게 나오지 않는다 | P95 응답시간 2초 초과 | P95 2초 이하 |

이 표가 있어야 부하 테스트와 알림도 방향을 잃지 않는다. k6는 "많이 때려보기"가 아니라 SLO를 깨뜨리는 조건을 재현하는 도구가 되고, Grafana나 Alertmanager는 예쁜 대시보드가 아니라 사용자의 기대가 깨지는 순간을 보는 장치가 된다.

## API 변경은 PR에서 먼저 드러나야 한다

프론트엔드와 맞닿는 변경은 코드가 아니라 계약 변경이다. 엔드포인트, 요청 파라미터, 응답 JSON, 예외 코드, 프론트 주의사항이 빠지면 구현은 끝났어도 연동은 시작하기 어렵다.

그래서 PR 템플릿에 프론트엔드 연동 정보를 직접 쓰도록 만들었다. 핵심은 "무엇을 만들었다"보다 "프론트가 무엇을 믿고 호출하면 되는가"를 남기는 것이다.

원본 문서: [.github/pull_request_template.md:14-45](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/.github/pull_request_template.md#L14-L45)

````markdown
## 프론트엔드 연동 가이드 (API 명세)

**1. 주요 엔드포인트**
- `[GET/POST/PUT/DELETE]` `/api/...` : (API 설명)

**2. 요청 파라미터 (Request)**
| 파라미터명 | 위치 (Query/Body/Path) | 필수 여부 | 설명 |
|---|---|---|---|

**3. 정상 응답 예시 (200 OK)**
```json
{
  // 정상 응답 JSON 복사 붙여넣기
}
```

**4. 프론트엔드 참고 및 주의사항**
- 예: `type=COURSE`일 때 `courseId` 누락 시 HTTP 400 발생

## 주요 에러 코드 및 예외
- `에러 코드` : 발생 조건 및 설명
````

이 방식의 장점은 API 변경을 리뷰 흐름 안으로 끌어온다는 점이다. 프론트와 백엔드가 따로 문서를 찾기 전에, PR에서 "이 변경이 화면에 어떤 영향을 주는가"를 먼저 보게 된다.

하지만 이것만으로는 충분하지 않다. 템플릿은 작성 누락을 줄일 뿐, 실제 API와 문서가 일치하는지 자동으로 보장하지는 않는다. 그래서 팀 API 표에는 단순 URL만 두지 않고, 아래 항목을 같이 관리했다.

관련 문서: [SCHEDULE_API.md:1-21](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/docs/SCHEDULE_API.md#L1-L21), [ONBOARDING_API.md](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/docs/ONBOARDING_API.md)

```text
API명칭 / METHOD / URL / 권한 / 개발 상황 / 작성완료
DB 변경 / 캐싱 / 이벤트/SSE / 기술 메모 / 운영 메모
```

이 표에서 중요한 칸은 `기술 메모`와 `운영 메모`였다. 예를 들어 영상 진도 API는 "90% 이상 시청하면 완료로 볼 것인가", "완료 영상 수가 통계에 반영되는가"처럼 프론트 화면과 백엔드 정책이 동시에 맞아야 했다. 순공시간 API는 "초 단위인지 분 단위인지", "KST 기준인지", "세션 종료와 통계/잔디 갱신이 같은 트랜잭션인지"를 확인해야 했다.

즉 API 문서는 엔드포인트 목록이 아니라, 화면·정책·DB·운영 기준을 같이 맞추는 장소가 되어야 했다.

## 문서가 필요했던 실제 연동 사례

팀 수정사항 문서에는 실제로 연동 중 발견된 불일치가 남아 있었다. 그대로 공개하면 계정, 주문번호, 금액 같은 값이 섞일 수 있어 블로그에는 문제 유형만 남긴다.

- 관리자 결제 목록에서 실제 결제 완료 건이 누락됐다. 학생 본인 결제 내역에는 `PAID`로 보이지만 관리자 목록에는 나오지 않았다. 원인은 삭제된 구독 플랜과의 조인 가능성이 있었고, 해결 방향은 `LEFT JOIN`이나 결제 당시 표시명 스냅샷 보존이었다.
- 구독 플랜 benefits 배열에서 "AI 학습 스케줄러 이용 가능" 항목이 빠졌다. 프론트는 이미 6개 혜택 기준으로 배포돼 있었고, 서버 응답은 5개만 내려주고 있었다. 이건 로직 버그라기보다 FE/BE가 같은 상품 계약을 보고 있는지의 문제였다.
- 강의 진도율에서 90% 이상 시청 인정 기준과 완료 표시 기준이 어긋났다. 어떤 영상은 95%까지 봤는데 완료가 아니었고, 상단 진도율의 영상 개수도 실제 총 영상 수와 맞지 않았다.
- 커뮤니티 전체 피드와 질문 게시판 API의 응답 필드가 달랐다. 같은 질문글인데 전체 피드에서는 과목 정보가 빠지고, 질문 게시판 전용 API에서는 정상 제공됐다.
- SSE 알림 스트림이 60초 뒤 끊겼다. 프론트 로그와 AWS ALB 기본 idle timeout 60초가 맞물려 보여, 서버 heartbeat나 인프라 timeout 조정이 필요한 운영 이슈로 봐야 했다.
- 타임테이블 화면은 하루 총 순공시간이 아니라 세션별 시작/종료 시각이 필요했다. 이미 세션 단위 데이터가 있다면 `GET /api/study-timers/sessions?date=YYYY-MM-DD` 같은 조회 API를 추가하는 방식으로 계약을 다시 잡을 수 있었다.

이 사례들은 모두 "누가 빨리 답장했는가"보다 "어느 문서에 무엇을 남겼는가"가 중요했다. API 표에는 `URL`, `METHOD`, `개발 상황`뿐 아니라 `DB 변경`, `캐싱`, `이벤트/SSE`, `운영 메모`가 있어야 같은 문제가 반복될 때 원인을 좁힐 수 있다.

## Issue와 PR은 작업 로그가 되어야 한다

팀 GitHub 매뉴얼도 단순 사용법보다 작업 로그 관점에서 쓸 만했다. 기본 흐름은 이렇게 잡았다.

원본 문서: [docs/WORKFLOW.md:5-24](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/docs/WORKFLOW.md#L5-L24)

```text
Issue 생성
-> 담당자 지정
-> 브랜치 생성
-> 작업 및 테스트
-> Pull Request 생성
-> 1명 이상 리뷰
-> merge
-> Issue 상태 변경
```

기능 개발은 `1 Feature = 1 PR`로 제한하고, 개발 시작 전 이슈를 먼저 등록하게 했다. PR 본문에는 `Closes #이슈번호`를 포함해 머지 시 이슈가 닫히도록 했다. 이 규칙이 있어야 나중에 "이 기능은 누가, 어떤 이슈에서, 어떤 PR로 끝냈는가"를 추적할 수 있다.

이 방식이 완벽한 것은 아니다. Issue를 만드는 행위 자체가 품질을 보장하지는 않는다. 하지만 작업 과정에서 고민, 결정 근거, 결과물 링크가 댓글과 PR에 남으면 인수인계와 회고의 최소 단위가 생긴다.

## Swagger는 문서가 아니라 API 계약의 출입구다

`/v3/api-docs`가 500으로 죽은 적이 있었다. 겉으로 보면 문서 페이지 하나가 안 열리는 문제지만, 팀 관점에서는 프론트엔드가 API 계약을 확인하는 통로가 막힌 것이다.

원인은 의존성 충돌이었다. `anthropic-java`가 끌고 온 `swagger-annotations`가 springdoc이 기대하는 annotation 계열과 충돌했다. 그래서 빌드 파일에서 충돌 의존성을 명시적으로 제외했다.

원본 코드: [build.gradle:39-73](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/build.gradle#L39-L73)

```gradle
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.16'

// springdoc의 swagger-annotations-jakarta와 패키지가 겹쳐
// 구버전 Schema가 먼저 로드되면 /v3/api-docs가 NoSuchMethodError로 죽는다.
implementation('com.anthropic:anthropic-java:2.34.0') {
    exclude group: 'io.swagger.core.v3', module: 'swagger-annotations'
}
```

여기서 협업 포인트는 "Swagger를 고쳤다"가 아니다. API 문서가 열리는 상태를 팀 연동의 기본 조건으로 본 것이다. PR에 API 변경 내용을 적고, Swagger에서 실제 스펙을 확인하고, Postman으로 성공과 실패 케이스를 검증하는 흐름이 있어야 문서와 구현의 거리가 줄어든다.

## DB 마이그레이션도 협업 계약이다

DB 변경은 백엔드 내부 작업처럼 보이지만, 실제로는 팀 전체 배포 가능성을 결정한다. Flown LMS에서도 Flyway 마이그레이션 버전 누락으로 운영 서버가 크래시 루프에 빠진 적이 있었다. 기능 하나가 실패한 것이 아니라 애플리케이션 자체가 올라오지 않는 문제였다.

기본 설정은 Hibernate가 스키마를 자동으로 바꾸지 않고, Flyway가 적용한 스키마와 엔티티가 맞는지 검증하게 했다.

원본 코드: [application.yaml:34-58](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/resources/application.yaml#L34-L58)

```yaml
spring:
  jpa:
    hibernate:
      # PR CI에서 Flyway 마이그레이션 적용 후 validate 부팅을 검증한다.
      ddl-auto: validate

  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1
    out-of-order: false
```

팀 문서에는 DB 구조 변경 절차도 따로 뒀다. 순서는 단순했다.

원본 문서: [docs/DB_MIGRATION_RULES.md:8-52](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/docs/DB_MIGRATION_RULES.md#L8-L52), [docs/DEV_RULES.md:7-15](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/docs/DEV_RULES.md#L7-L15)

```text
Entity 수정
-> db/migration 에 마이그레이션 SQL 작성
-> 서버 실행
-> Flyway SQL 적용
-> Hibernate validate로 Entity와 DB 일치 검사
-> PR 생성 시 마이그레이션 파일 포함
```

별도 DBA가 없는 팀이라도 DB 변경 권한을 모두에게 열어두면 더 위험했다. 그래서 도메인 개발자는 JPA와 dev DB의 DML 중심으로 작업하고, 스키마 변경은 마이그레이션 담당자가 버전과 내용을 확인하는 식으로 역할을 나눴다. Slack `#db`에는 담당자, 희망 버전, 변경 목적, 대상 테이블, nullable, default, index, FK, 기존 API 영향을 쓰게 했다. 형식이 조금 번거롭더라도 "컬럼 하나 추가"가 배포 실패로 이어지는 일을 줄이는 쪽이 더 중요했다.

금지 사항도 명확히 했다. `ddl-auto: update` 사용 금지, Entity만 수정하고 마이그레이션 파일을 누락하는 것 금지, DB 콘솔에서 직접 `ALTER/CREATE`를 치고 파일을 남기지 않는 것 금지, 이미 공유·적용된 `Vn` 파일 수정 금지다. 이미 적용된 마이그레이션을 고쳐서 맞추기보다, 누락 사항은 다음 버전 파일로 남기는 쪽이 추적 가능하다.

다만 운영에서는 병렬 개발 중 마이그레이션 버전 레인이 갈라진 상태에서 낮은 버전이 뒤늦게 들어오는 문제가 있었다. 이때 `out-of-order=false`만 고집하면 이미 상위 버전이 적용된 운영 DB에서 누락된 하위 버전을 다시 적용하지 못해 부팅이 막힐 수 있었다.

그래서 운영 프로필에는 장애 복구 관점에서 `out-of-order=true`를 둔 상태였다.

원본 코드: [application-prod.yaml:10-17](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/resources/application-prod.yaml#L10-L17)

```yaml
# application-prod.yaml
spring:
  flyway:
    # 담당자별 버전 레인이 병렬로 머지되어 낮은 버전이 뒤늦게 들어오는 구조라,
    # out-of-order=false면 "Detected resolved migration not applied to database"로 부팅이 막힌다.
    # 2026-07-14 프로덕션 장애: V3.3.3 누락으로 크래시 루프 발생.
    out-of-order: true
```

이 선택은 이상적인 정답이라기보다 당시 팀 구조를 반영한 완충장치에 가깝다. 장기적으로는 마이그레이션 번호 정책을 단일화하거나, 도메인별 버전 레인을 명확히 나누고 머지 전에 검증하는 쪽이 더 낫다.

그래서 PR CI에 스키마 드리프트 게이트를 추가했다. 임시 MySQL에 Flyway 마이그레이션을 적용한 뒤, Hibernate `validate`로 애플리케이션을 실제 부팅해 본다.

원본 코드: [.github/workflows/pr-ci.yml:117-155](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/.github/workflows/pr-ci.yml#L117-L155)

```yaml
- name: 스키마 드리프트 게이트
  env:
    SPRING_PROFILES_ACTIVE: test
    DB_URL: jdbc:mysql://127.0.0.1:3306/Hard-Click
  run: |
    JAR=$(ls build/libs/*.jar | grep -v plain | head -1)
    java -jar "$JAR" > app.log 2>&1 &
    APP_PID=$!

    for i in $(seq 1 60); do
      if grep -q "Started .*Application in" app.log; then
        echo "Flyway 적용 + Hibernate validate 부팅 성공"
        kill $APP_PID 2>/dev/null || true
        exit 0
      fi

      if grep -qiE "Schema-validation|SchemaManagementException|Migration .* failed|FlywayException" app.log; then
        echo "스키마 드리프트/마이그레이션 오류 감지"
        tail -n 60 app.log
        kill $APP_PID 2>/dev/null || true
        exit 1
      fi

      sleep 2
    done

    echo "타임아웃: 부팅 실패"
    tail -n 60 app.log
    kill $APP_PID 2>/dev/null || true
    exit 1
```

이 게이트가 있으면 DB 변경은 "내 로컬에서 됐다"가 아니라 "마이그레이션 적용 후 애플리케이션이 뜬다"까지 PR 단계에서 확인된다. 협업에서는 이 차이가 크다. 깨진 스키마 변경이 머지된 뒤 누군가 배포 중에 발견하는 것보다, 머지 전에 자동으로 막히는 편이 훨씬 안전하다.

## 오류 알림은 담당자를 좁힐 수 있어야 한다

운영 중 500 오류가 발생했을 때 "서버 터졌어요"만 공유되면 조사가 늦어진다. 어떤 도메인에서, 어떤 URL에서, 어떤 예외 타입으로 터졌는지 알림에 같이 붙어야 담당자를 빠르게 좁힐 수 있다.

그래서 전역 예외 처리에서 예상하지 못한 500 오류를 Sentry로 보낼 때 도메인, URL, HTTP method, 예외 타입을 태그로 남겼다.

원본 코드: [GlobalExceptionHandler.java:268-300](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/src/main/java/com/wanted/backend/global/exception/GlobalExceptionHandler.java#L268-L300)

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleAllException(
        Exception e,
        HttpServletRequest request) {

    String path = request.getRequestURI();
    log.error("[System Error] Path: {}, Message: {}", path, e.getMessage(), e);

    Sentry.configureScope(scope -> {
        scope.setTag("domain", extractDomain(path));
        scope.setTag("exceptionType", e.getClass().getSimpleName());
        scope.setTag("path", path);
        scope.setTag("method", request.getMethod());
    });
    Sentry.captureException(e);

    ErrorResponse response = ErrorResponse.create()
            .errorCode(ErrorCode.INTERNAL_SERVER_ERROR.getCode())
            .message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
            .path(path);

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
}
```

`extractDomain`은 URL 첫 구간으로 담당 도메인을 추론한다. 완벽한 매핑은 아니지만, 알림만 보고 "orders 쪽인지", "quiz 쪽인지", "identity 쪽인지"를 먼저 나눌 수 있다.

이것도 완성된 운영 체계는 아니다. Sentry 알림은 늘어날수록 피로도가 생기므로 severity, ignore rule, sampling 기준이 필요하다. 다만 작은 팀에서는 첫 단계로 "오류를 조용히 로그에만 남기지 않는다"가 중요했다.

## Slack 알림은 애플리케이션 밖에서 보낸다

운영 알림을 설계할 때 처음에는 Spring Boot가 Slack Webhook을 직접 호출하는 방식도 고민할 수 있었다. 하지만 앱과 Alertmanager가 동시에 Slack을 보내면 어느 경로에서 보낸 알림인지 헷갈리고, 각자 다른 throttle 기준을 가지면서 장애 타임라인이 흐려진다.

그래서 역할을 나눴다.

```text
Spring Boot Micrometer
-> Prometheus scrape / alert rule 평가
-> Alertmanager 라우팅
-> Slack
```

Spring Boot는 counter와 timer를 쌓는 데 집중한다. 알림 조건 평가는 Prometheus alert rule이 맡고, 묶기, 반복 알림, Slack 채널 선택은 Alertmanager가 맡는다. alert rule에는 `domain` 레이블을 붙이고, Prometheus `external_labels`로 `env`를 붙여 운영에서는 도메인별 채널로, 테스트나 시연 환경에서는 통합 채널로 보낼 수 있게 했다.

원본 코드: [monitoring/alert-rules/payment.yml:26-36](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/monitoring/alert-rules/payment.yml#L26-L36), [monitoring/prometheus.yml:1-8](https://github.com/Hard-Click/Hard-Click-BackEnd/blob/ea50993a49340ee2bce8b53b439211c541c4da81/monitoring/prometheus.yml#L1-L8)

```yaml
- alert: PaymentLatencyP95High
  expr: |
    histogram_quantile(0.95,
      rate(payment_processing_duration_seconds_bucket[5m])) > 2
  for: 5m
  labels:
    severity: warning
    domain: payment
  annotations:
    summary: "[PAYMENT] 결제 응답 P95 2초 초과"
```

이 구조에서 중요한 건 Slack 메시지 자체가 아니다. 앱은 비즈니스 이벤트와 지표를 남기고, 알림 시스템은 그 지표를 기준으로 담당자와 환경을 좁힌다. 그래야 결제, 주문, 커뮤니티, 인증 같은 도메인이 섞여 있어도 "어느 팀원이 봐야 하는 문제인지"를 알림만 보고 줄일 수 있다.

## 협업 글에서 빼야 하는 말

이번 글을 다시 쓰며 의도적으로 뺀 표현이 있다.

- Jira를 정리했으니 협업이 좋아졌다는 식의 문장
- Swagger를 붙였으니 API 협업이 끝났다는 식의 문장
- Sentry를 붙였으니 운영 품질이 완성됐다는 식의 문장

도구 이름만 나열하면 협업이 아니라 도구 사용기가 된다. 내가 남기고 싶은 것은 도구 자체가 아니라 기준이다.

- API 변경은 PR에서 요청, 응답, 예외, 프론트 주의사항으로 드러나야 한다.
- Swagger는 프론트가 확인하는 계약 표면이므로 500으로 죽으면 연동이 막힌 것으로 본다.
- DB 마이그레이션은 배포 가능성을 바꾸므로 CI에서 validate 부팅까지 확인한다.
- 500 오류는 담당 도메인을 좁힐 수 있는 정보와 함께 공유한다.
- SLO는 사용자 기대, 실패 조건, 지표, 알림 조건으로 이어져야 한다.
- Slack 알림은 Spring Boot가 직접 보내지 않고 Prometheus와 Alertmanager 파이프라인에서 라우팅한다.
- 수정사항 문서는 불만 목록이 아니라, 화면과 API 계약이 어긋난 사례 저장소로 본다.

## 남은 한계

아직 부족한 점도 분명하다. Swagger, Notion, Postman을 함께 쓰면 최신 상태를 여러 곳에 반영해야 한다. PR 템플릿은 누락을 줄일 수 있지만 자동 검증은 아니다. API 계약 테스트나 OpenAPI diff, 문서 변경 이력 관리까지 붙이면 더 안정적일 것이다.

Flyway도 마찬가지다. 운영 `out-of-order=true`는 당시 장애를 막기 위한 선택이었지만, 마이그레이션 작성 규칙이 정리되지 않으면 비슷한 문제가 반복될 수 있다. 장기적으로는 버전 충돌이 생기지 않는 규칙과 리뷰 기준이 필요하다.

협업은 "좋은 분위기"가 아니라 같은 변경을 같은 기준으로 보는 구조라고 느꼈다. 내가 이 프로젝트에서 한 일은 그 구조를 완성한 것이 아니라, API 계약, 스키마 검증, 오류 알림처럼 깨졌을 때 팀 전체를 멈추게 하는 지점을 코드와 문서 흐름 안에 드러내려 한 것이다.
