# 잘못된 요청 하나로 500 오류와 에러 알림 메일이 났다

- **종류**: 찾은 문제 (보안, 운영). 누구나 요청 하나로 500 오류를 내고, 운영자에게 에러 알림 메일을 보낼 수 있었다
- **발견**: 2026-11-20, JM-92 PR 전 검토 (정확성 검토와 개인정보 반박 확인)에서 깨진 주소 값 하나를 찾았다. 고치면서 잘못된 요청을 묶어 보내 보고, JM-107의 PR 전 검토에서 더 찾아 원인이 아홉 가지가 됐다. 고치기 전까지는 비공개 기록 저장소에만 적었다
- **상태**: 해결 (JM-107, PR #54). 운영에서의 확인은 배포 뒤 "기록"에 더한다
- **영향**: 에러 경보(`macros-app-error`)는 로그에 ERROR 한 줄만 찍혀도 울린다. 잘못된 요청으로 경보가 울리면 진짜 서버 오류가 그 사이에 묻힌다. 화면을 연 사람에게는 "서버에 문제가 생겼습니다"가 보였다

## 현상 (고치기 전)

로컬 서버(main `3d25b74`를 그대로 꺼낸 복사본)에 잘못된 요청을 하나씩 보내고, 운영과 같은 JSON 로그에서 그 요청이 남긴 ERROR 줄을 셌다 (11-20). 운영에서는 재지 않았다. 500이 나면 그 ERROR 줄이 운영자에게 메일로 가기 때문이다.

| 원인 | 예 | 고치기 전 | 고친 뒤 |
|---|---|---|---|
| 1. 서버(Tomcat)가 값을 읽지 못함 | `/?page=%zz`, `%ED%95`, 이름이 깨진 값, 값 1,001개, 2MB 넘는 form, 댓글과 피드백 form의 깨진 값 | 500, ERROR 한 줄씩 (21가지) | 400 (2MB 넘는 form은 413), WARN 한 줄씩 |
| 2. 아주 큰 쪽 번호 | `/?page=2147483647`, `/feedback?page=2147483647` | 500, ERROR (2가지) | 200, 마지막 쪽 |
| 3. 주소의 `jsp_precompile` | `/terms?jsp_precompile=abc`, `/terms?jsp_precompile` | 값이 있으면 500과 ERROR 두 줄, 값이 없으면 빈 화면(200, 0바이트) | 200, 화면 그대로 |
| 4. JSON을 받지 않는다고 한 요청의 API 오류 | `Accept: text/html`로 운영자 열쇠 없이 `/api/admin/stats`, 없는 글의 분석 | 500, ERROR | 401, 404 JSON |
| 5. PUT, PATCH, DELETE로 보낸 깨진 form | `PUT /`에 `a=%zz` | 500, ERROR (Tomcat이 남김) | 405 |
| 6. 모양이 잘못된 업로드 | 경계 표시가 없음, 마지막 경계 없이 끝남, 칸 머리말이 잘못됨 | 500, ERROR (4가지) | 400 |
| 7. form 칸 이름의 대괄호와 점 | `title[0]=a`, `image.a=1` | 500, ERROR (4가지) | 400 |
| 8. 비밀번호 칸이 없는 form | 피드백, 댓글 쓰기에서 `password` 칸을 뺌 | 500, ERROR (DB의 NOT NULL) | 입력 화면으로 돌려보냄 (200, 302) |
| 9. 응답을 받다가 끊은 연결 | 사진이나 CSS를 받다가 다른 화면으로 감 | ERROR (소스로 확인. 로컬에서는 재현되지 않음) | ERROR 없이 끝냄 |

합계 (같은 69가지): 5xx 40가지 → 0, ERROR 42줄 → 0줄, 빈 화면 3가지 → 0. WARN은 14줄 → 29줄이다 (원인 1의 21가지가 한 줄씩 남긴다). 처음 묶음은 1~4의 56가지(5xx 27, ERROR 29줄)였고, PR 전 검토 에이전트 두 개가 5~9를 더 찾았다.

원인 9는 받는 창을 1KB로 줄인 소켓으로 430KB 사진을 부르고 0 ~ 50 ms 뒤에 끊어도(24번) 로컬에서는 ERROR가 나지 않았다. 내 PC 안의 연결은 버퍼가 커서 사진이 끝까지 써진 것으로 보인다. 운영에서는 사용자가 끊으면 nginx가 앱 쪽 연결도 끊으므로 생길 수 있다.

## 원인

1. Tomcat 11은 주소나 form의 값을 처음 읽을 때 글자가 깨져 있거나(퍼센트 인코딩, UTF-8), 값이 1,000개를 넘거나, form이 2MB를 넘으면 `InvalidParameterException`을 던지고 알맞은 상태(400, 413)를 예외에 담아 둔다. 화면 예외 처리(`PageExceptionHandler`)는 Spring의 기본 예외처럼 자기 상태를 알려 주는 예외만 400번대로 보고, 이 예외는 모르는 예외로 보아 500과 ERROR(스택 트레이스)를 남겼다. 예외 문구에는 보낸 값이 그대로 들어 있어 로그에도 남았다
2. Spring Data JPA는 앞 쪽들의 글 수(offset)가 int를 넘으면 예외를 던진다. 피드와 피드백 목록은 범위를 넘는 쪽이면 마지막 쪽을 다시 읽는데, 그 전 첫 읽기에서 예외가 났다. 공지 목록은 쪽 수로 먼저 자르고 있어 괜찮았다
3. JSP 엔진(Jasper)은 주소 어디에든 `jsp_precompile` 글자가 있으면 미리 컴파일하라는 신호로 본다. 값이 true, false가 아니면 예외를 던지고, 값이 없으면 화면을 그리지 않고 컴파일만 한다. 찍먹은 이 기능을 쓰지 않는다
4. API의 오류 응답이 형식을 요청의 Accept로 고르게 두어서, JSON을 받지 않는다고 한 요청에는 오류 JSON을 쓰지 못했다. 그러면 오류 처리 자체가 실패해 원래 예외가 Tomcat까지 올라갔다. 브라우저 주소창은 Accept에 `*/*`를 넣어 보내므로 401로 잘 나갔다
5. Spring Boot가 기본으로 켜 두는 `FormContentFilter`가 PUT, PATCH, DELETE의 form 본문을 Spring MVC보다 앞에서 읽다가 예외를 냈다. 예외 처리보다 앞이라 Tomcat이 ERROR를 남겼다
6. 업로드 본문은 Controller를 찾기 전에 읽는다. 본문의 모양이 잘못돼 Spring이 `MultipartException`을 던지면 모르는 예외(500)로 떨어졌다. 어느 POST 주소로 보내도 같았다
7. form 칸 이름에 `[0]`이나 `.`이 붙으면 Spring이 값을 넣을 곳을 찾지 못해 `InvalidPropertyException`을 던진다. 이것도 모르는 예외였다
8. 비밀번호 칸의 검사가 `@Size(min = 4, max = 20)`뿐이었다. 칸이 아예 없으면(null) `@Size`는 통과시키므로, 비밀번호 없이 저장하다 DB에서 막혔다. 글 올리기는 사진을 저장소에 올린 뒤에야 막혀 남는 파일이 생길 수 있었다
9. Spring은 사용자가 끊은 연결을 예외 처리 메서드 안에서 난 예외일 때만 걸러 주고, 원래 예외는 그대로 우리 예외 처리로 넘긴다. 우리 처리는 그것을 예상하지 못한 예외로 보아 ERROR를 남겼다

## 해결 (JM-107)

1. `PageExceptionHandler`와 `ApiExceptionHandler`에 `InvalidParameterException` 처리를 더했다. 예외에 담긴 400번대 상태로 답하고, 로그는 `Invalid request parameter: status=400` 한 줄(WARN)만 남긴다. 보낸 값은 남기지 않는다. 화면 문구는 "요청 내용이 올바르지 않습니다." (413이어도 사진 한도 문구를 쓰지 않는다)
2. `PageNav.readablePage`가 쪽 번호를 넘지 않는 가장 큰 쪽으로 줄인다. 그 쪽에는 글이 없으므로 피드와 피드백 목록이 지금처럼 마지막 쪽을 다시 읽는다
3. `application.properties`에서 Jasper의 미리 컴파일 신호 이름(`jspPrecompilationQueryParameter`)을 빈칸이 든 이름으로 바꿨다. 요청 줄의 주소에는 빈칸이 그대로 들어올 수 없어서(빈칸은 `%20`이나 `+`로 온다) 그 신호가 다시 생기지 않는다
4. API의 오류 응답은 형식(Content-Type)을 JSON으로 정해 둔다. 형식을 미리 정하면 Spring이 Accept로 고르지 않는다
5. `spring.mvc.formcontent.filter.enabled=false`. form을 받는 PUT, DELETE가 없고, PATCH(운영자 API)는 JSON을 받는다
6. 업로드 본문의 모양이 잘못된 `MultipartException`은 400. Tomcat이 "요청의 모양이 잘못됐다"고 본 예외(경계 표시 없음, 본문이 끊김, 업로드 형식이 아님, 쓸 수 없는 파일 이름)나 Spring이 칸 머리말을 읽지 못한 예외가 원인일 때만이다. 디스크에 쓰지 못함, 임시 폴더 없음 같은 서버 쪽 문제는 그대로 500과 ERROR로 남긴다 (테스트로 지킨다)
7. `InvalidPropertyException`은 `TypeMismatchException`처럼 400
8. 글, 댓글, 피드백 쓰기 form의 비밀번호에 `@NotNull`을 더했다
9. 화면과 API의 예외 처리에서 사용자가 끊은 연결(Spring의 `DisconnectedClientHelper`가 예외 종류와 문구로 가린다)은 ERROR 없이 끝낸다

## 재발 방지

- `BrokenRequestValueTest`: 실제 Tomcat을 빈 포트로 띄우고 잘못된 요청을 소켓으로 보내(Java의 URI와 HttpClient는 깨진 주소를 보내기 전에 거절한다), 400번대와 ERROR 0줄을 확인한다 (13개)
- 예외 처리 테스트 6개: 같은 예외의 400과 413(화면과 API), JSON을 받지 않는 요청의 오류 JSON, 모양이 잘못된 업로드는 400이고 디스크와 임시 폴더 문제는 500, 끊긴 연결은 ERROR 없음. 쪽 번호는 `PageNavTest`
- 새 테스트 19개(실제 Tomcat 13개, 예외 처리 6개)는 고치기 전 코드(main 복사본)에서 모두 실패하고, 기존 테스트는 그대로 통과했다. 고친 코드에서는 전체 306개가 통과한다
- 테스트 설정: 실제 Tomcat을 띄우는 테스트로 스프링 컨텍스트가 하나 늘자 전체 테스트가 MySQL의 연결 한도(151)에 걸렸다("Too many connections", 테스트 하나의 컨텍스트가 뜨지 못함). 컨텍스트마다 미리 여는 연결을 10개에서 1개로 줄여, 전체 테스트 중 DB 연결의 최대가 한도(152) → 30이 됐다
- [미리 막은 문제: 알아채고 원인 찾기](prevent-observability.md) 8번

## 배운 것

- "400번대는 로그를 남기지 않는다"는 처리는 예외가 자기 상태를 알려 줄 때만 맞았다. 서버(Tomcat)나 Spring의 다른 예외는 모르는 예외(500)로 떨어졌다. 상태를 담은 예외는 그 상태를 쓰고, 요청이 잘못된 경우를 하나씩 이름으로 가려야 한다
- 경보는 서버 문제에만 울려야 쓸모가 있다. 사용자가 만들 수 있는 ERROR는 경보를 무디게 한다
- MockMvc는 Tomcat의 값 읽기와 JSP 엔진을 거치지 않는다. 이런 일은 실제 서버를 띄운 테스트로만 잡힌다
- 한 경우를 고칠 때 같은 종류를 묶어 보내 보면 더 나온다. 처음 하나(`?page=%zz`)에서 아홉 가지가 됐다

## 재는 법

- 로컬: 서버를 `--logging.structured.format.file=ecs`로 띄워 운영과 같은 JSON 로그를 파일로 남기고, 비공개 기록 저장소의 `python -I bad_request_values.py <포트> <로그 파일> <결과 json> [사진 폴더]`로 69가지를 보낸다. 표는 `bad_request_values_report.py`
- 운영 (배포 뒤): bot User-Agent로 몇 가지만 보내 400을 보고, 경보 `macros-app-error`가 그대로 정상인지 본다. 얼마나 자주 들어오는지는 CloudWatch Logs Insights에서 `filter message like "Invalid request parameter"`로 센다. 원인 9가 전에 운영에서 있었는지는 `filter @message like /ClientAbortException|Broken pipe|Connection reset/`로 본다

## 기록

- 비공개 기록 저장소 `update_picture/JM-107/`: `before-behavior.txt`, `draft1-behavior.txt`(56가지), `before2-behavior.txt`, `draft2-behavior.txt`(69가지와 받다가 끊기 24번). 요청 하나하나는 같은 이름의 json, 화면 사진(`before-`, `draft1-`)
- Jira JM-107
- 같은 검토들에서 찾았지만 이번에 고치지 않은 것은 아직 비공개 기록에만 있다
