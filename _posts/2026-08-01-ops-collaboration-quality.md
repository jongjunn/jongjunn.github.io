---
layout: post
title: "협업을 도구가 아니라 계약으로 만들기: API, DB, 오류 공유 기준"
date: 2026-08-01 20:00:00 +0900
categories: [협업, 운영]
tags: [API계약, Swagger, Flyway, Sentry, CI]
---

협업은 회의를 많이 하거나 도구를 많이 쓰는 것으로 좋아지지 않았다. Flown LMS를 만들며 실제로 막혔던 지점은 더 단순했다. 프론트엔드는 어떤 요청과 응답을 믿고 개발해야 하는지, 백엔드는 어떤 DB 변경이 배포를 막을 수 있는지, 장애가 났을 때 누가 어떤 정보를 보고 움직여야 하는지가 불명확했다.

그래서 이 글은 "협업 도구를 정리했다"는 이야기가 아니다. 팀원이 같은 상태를 보게 만들기 위해 API 계약, 스키마 변경, 오류 알림을 어디에 남기고 어떻게 검증했는지에 대한 기록이다.

## 협업 문제를 다시 정의하기

초기에는 Notion, Swagger, Postman, Slack, PR 설명이 모두 따로 움직였다. 각각은 필요했지만, 변경 사항이 여러 곳에 흩어지면 다음 문제가 생긴다.

- 프론트엔드는 문서를 보고 호출했는데 실제 엔드포인트가 다르다.
- 응답 필드나 enum이 바뀌었는데 화면 작업자가 늦게 알게 된다.
- DB 마이그레이션 파일은 머지됐지만 운영 서버가 부팅 단계에서 멈춘다.
- 500 오류가 발생해도 어느 도메인 담당자가 봐야 하는지 알림만 보고 판단하기 어렵다.

이 문제를 "소통이 부족했다"로만 정리하면 해결이 흐려진다. 내가 잡은 기준은 이것이었다.

> 협업 정보는 말로 전달하는 것이 아니라, 변경이 발생하는 지점에 남아야 한다.

## API 변경은 PR에서 먼저 드러나야 한다

프론트엔드와 맞닿는 변경은 코드가 아니라 계약 변경이다. 엔드포인트, 요청 파라미터, 응답 JSON, 예외 코드, 프론트 주의사항이 빠지면 구현은 끝났어도 연동은 시작하기 어렵다.

그래서 PR 템플릿에 프론트엔드 연동 정보를 직접 쓰도록 만들었다. 핵심은 "무엇을 만들었다"보다 "프론트가 무엇을 믿고 호출하면 되는가"를 남기는 것이다.

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

하지만 이것만으로는 충분하지 않다. 템플릿은 작성 누락을 줄일 뿐, 실제 API와 문서가 일치하는지 자동으로 보장하지는 않는다. 그래서 Swagger와 Postman 검증을 함께 기준으로 둬야 했다.

## Swagger는 문서가 아니라 API 계약의 출입구다

`/v3/api-docs`가 500으로 죽은 적이 있었다. 겉으로 보면 문서 페이지 하나가 안 열리는 문제지만, 팀 관점에서는 프론트엔드가 API 계약을 확인하는 통로가 막힌 것이다.

원인은 의존성 충돌이었다. `anthropic-java`가 끌고 온 `swagger-annotations`가 springdoc이 기대하는 annotation 계열과 충돌했다. 그래서 빌드 파일에서 충돌 의존성을 명시적으로 제외했다.

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

다만 운영에서는 병렬 개발 중 마이그레이션 버전 레인이 갈라진 상태에서 낮은 버전이 뒤늦게 들어오는 문제가 있었다. 이때 `out-of-order=false`만 고집하면 이미 상위 버전이 적용된 운영 DB에서 누락된 하위 버전을 다시 적용하지 못해 부팅이 막힐 수 있었다.

그래서 운영 프로필에는 장애 복구 관점에서 `out-of-order=true`를 둔 상태였다.

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

## 남은 한계

아직 부족한 점도 분명하다. Swagger, Notion, Postman을 함께 쓰면 최신 상태를 여러 곳에 반영해야 한다. PR 템플릿은 누락을 줄일 수 있지만 자동 검증은 아니다. API 계약 테스트나 OpenAPI diff, 문서 변경 이력 관리까지 붙이면 더 안정적일 것이다.

Flyway도 마찬가지다. 운영 `out-of-order=true`는 당시 장애를 막기 위한 선택이었지만, 마이그레이션 작성 규칙이 정리되지 않으면 비슷한 문제가 반복될 수 있다. 장기적으로는 버전 충돌이 생기지 않는 규칙과 리뷰 기준이 필요하다.

협업은 "좋은 분위기"가 아니라 같은 변경을 같은 기준으로 보는 구조라고 느꼈다. 내가 이 프로젝트에서 한 일은 그 구조를 완성한 것이 아니라, API 계약, 스키마 검증, 오류 알림처럼 깨졌을 때 팀 전체를 멈추게 하는 지점을 코드와 문서 흐름 안에 드러내려 한 것이다.
