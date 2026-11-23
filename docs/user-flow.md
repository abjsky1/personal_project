# 사용자 흐름 기록 (JM-92)

사람들이 어디서 들어와서, 어느 화면에서 어느 화면으로 가고, 글을 올리기까지 가는지를 나중에 2차 데이터로 분석하려고 남긴다 (운영자 요청, 2026-11-19). 첫 조각은 서버에서 화면 이동을 기록하는 것이다. 표는 기획서 12절 `page_views`, 결정은 18절 "사용자 흐름 기록".

- 쌓이기 시작: PR #54가 배포된 때부터. 그 전의 흐름은 nginx 기록의 합계로만 알 수 있다 (6절)
- 보관: 지우지 않고 모두 보관한다 (운영자 결정 11-20). 지금 사람의 화면 조회로 보면 한 해에 15만 줄쯤이다. 하루에 받는 줄은 2만 줄까지 (`app.page-flow.daily-limit`, 2절)

## 1. 무엇을 남기나

한 줄 = 사람이 화면을 하나 받은 것(GET, 200, HTML), 또는 글 올리기가 새 글로 넘어간 것(`UPLOAD`).

| screen | 주소 | target |
|---|---|---|
| `FEED` | `/`, `/?page=2` | 요청한 쪽 번호 (없거나 0 이하면 1. 마지막 쪽보다 큰 번호는 화면이 마지막 쪽을 보여 줘도 요청한 번호) |
| `SEARCH` | `/search?q=라면`, `/search?q=라면&page=2` (2026-11-23, JM-52) | 요청한 쪽 번호 (`FEED`와 같은 규칙). 검색어는 남기지 않는다 |
| `POST` | `/posts/{번호}` | 글 번호 |
| `POST_NEW` | `/posts/new` | |
| `UPLOAD` | `POST /posts`가 새 글로 넘어감 (302) | 비움 |
| `NOTICE_LIST`, `NOTICE` | `/notice`, `/notice/{번호}` | 공지 번호 |
| `FEEDBACK_LIST`, `FEEDBACK`, `FEEDBACK_NEW` | `/feedback`, `/feedback/{번호}`, `/feedback/new` | 피드백 번호 |
| `PRIVACY`, `TERMS` | `/privacy`, `/terms` | |

- **방문**: 이미 쓰는 세션 하나. 세션은 화면을 처음 그릴 때 생기고 30분 동안 쓰지 않으면 끝난다. 방문 수(JM-86)를 세는 세션과 같다. 세션에 무작위 번호(`visit_key`)를 붙여 두고, 그 방문의 줄에 같은 번호와 순서(`seq` 1, 2, 3 ...)를 적는다
- **매 줄**: 시각(UTC), 방문 번호, 순서, 화면, 번호, 바로 앞 화면, 휴대폰인지 컴퓨터인지
- **바로 앞 화면**(`prev_screen`): 브라우저가 보내는 Referer(이 요청을 보낸 화면의 주소)가 이 사이트면 그 경로의 화면. 뒤로 가기를 하고 다른 글을 눌러도 실제로 누른 화면이 남는다. 입력이 틀려 다시 보여 준 글쓰기 화면(주소 `/posts`)은 글쓰기로 본다
- **이 방문이 쓰거나 고친 글의 번호는 남기지 않는다**: 글을 올리면 새 글로, 댓글을 달면 그 글로, 피드백을 쓰면 그 피드백으로 넘어간다. 그렇게 넘어간 글(새 글, 댓글을 단 글, 확정이나 메모를 고친 글, 쓴 피드백)은 그 방문이 끝날 때까지 `target`을 비운다 (다시 열어도). 흐름을 글쓴이와 번호로 잇지 않으려는 것이다
- **방문의 첫 줄에만, 들어온 곳**: 앱 종류(`app`: 카카오톡, 인스타그램 같은 앱 안의 브라우저나 브라우저 종류), 다른 사이트에서 왔으면 그 도메인(`ref_host`), 주소의 `utm_source`, `utm_medium`, `utm_campaign`, 링크 표시의 이름(`click_id`: `fbclid`, `gclid`, `igsh`, `igshid`)

만드는 곳: [`PageFlowInterceptor`](../backend/src/main/java/backend/visit/web/PageFlowInterceptor.java) (방문 수의 `VisitCountInterceptor` 옆, 같은 주소 범위는 `VisitConfig`), 값 규칙은 [`Screen`](../backend/src/main/java/backend/visit/model/entity/Screen.java), [`ClientApp`](../backend/src/main/java/backend/visit/model/entity/ClientApp.java), [`Device`](../backend/src/main/java/backend/visit/model/entity/Device.java), [`VisitEntry`](../backend/src/main/java/backend/visit/model/vo/VisitEntry.java), 저장은 [`PageFlowRecorder`](../backend/src/main/java/backend/visit/service/PageFlowRecorder.java).

**저장은 화면 응답 밖에서** 한다. 인터셉터는 줄(queue)에 넣기만 하고 바로 돌아가고, 기록 전용 일꾼 스레드 하나가 INSERT 한 문장으로 차례로 저장한다. 줄은 1000개까지이고 가득 차면 새 줄은 버린다. 서버를 끌 때(배포) 남은 줄은 5초까지 마저 저장한다. 처음에는 요청 안에서 JPA로 저장했는데, 재어 보니 사람 화면이 p50 8 ms 늦어져서 바꿨다 (6절).

## 2. 남기지 않는 것, 빼는 것

- **남기지 않는다**: IP, 세션 번호(JSESSIONID: 새면 남의 세션을 빌려 쓸 수 있다), User-Agent 전체, 주소 전체(다른 사이트는 도메인만, 검색어와 경로는 버린다. IP 주소와 localhost 같은 이름도 버린다), 이 방문이 쓰거나 고친 글의 번호, 링크 표시의 값, 우리 검색 칸에 친 검색어(`SEARCH` 줄은 쪽 번호만, 2026-11-23 JM-52)
- **utm 값은 정한 글자만**: 글자(한글 포함), 숫자, 빈칸, `._~+-`만 남기고 나머지(`<`, `>`, `"`, `=`, `@`, 보이지 않는 글자, 줄바꿈)는 지운 뒤 50자까지. 그래도 누구나 주소에 넣을 수 있는 값이라, 화면에 띄울 때는 반드시 `c:out`(JSP)이나 글자로만(자바스크립트 `textContent`) 넣고, 표 파일(CSV)로 내보낼 때는 맨 앞에 `'`를 붙인다
- **시각으로는 이을 수 있다**: 줄마다 시각이 있어서, 올리기 줄과 그 무렵 쓴 글이나 댓글의 작성 시각을 맞춰 보면 하루 10건쯤인 지금은 누가 쓴 방문인지 짐작할 수 있다. 시각을 흐리게 하면 화면 사이의 시간을 볼 수 없어서 두고, 대신 **흐름 기록을 글쓴이를 찾는 데 쓰지 않는다** (운영 규칙). utm 링크를 만들 때도 사람 이름을 넣지 않는다 (모두 보관하므로)
- **쿠키를 새로 만들지 않는다**. 같은 사람이 다른 날이나 다른 기기로 오면 다른 방문이다 (사람 수가 아니라 방문 수)
- **빼는 것**: bot(조회수와 같은 기준 `Visitors`), 관리자로 들어온 적이 있는 세션(로그인한 브라우저로 본 사용자 화면. 관리자 화면만 쓰고 로그아웃한 뒤에도 그 세션이 끝날 때까지 뺀다), 에러 화면(지워진 글의 404 등), 그리다 실패한 화면, 운영자 화면, API, 사진, css
- **섞일 수 있는 것**: 운영자가 관리자 로그인 없이 휴대폰으로 본 화면, 관리자로 들어가기 전에 같은 세션에서 본 화면
- 저장에 실패하거나 줄이 가득 차거나 하루 상한을 넘어도 화면은 그대로 나가고, 경고 로그(`Page view record failed`, `Page view dropped`, `Page flow skipped`)만 남는다. 저장 쪽 경고는 1분에 한 줄로 줄인다 (그 사이 건너뛴 수를 붙인다). 에러 알림은 울리지 않는다. 서버가 갑자기 죽으면 줄에 있던 몇 줄은 남지 않는다
- **하루 상한**: 쿠키를 안 쓰는 스크립트는 요청마다 새 세션이 생겨 줄마다 새 방문이 된다. 줄을 지우지 않으므로 하루에 받는 줄을 2만 줄로 막는다 (지금 하루 400쯤의 50배). 넘으면 그날 남은 줄은 버린다

## 3. 보는 법 (서버)

SSH로 서버에 들어가서 아래처럼 SQL을 넘긴다. DB 비밀번호는 컨테이너 안에서 `.env`로만 읽어 화면에 나오지 않는다 (관리자 기기 지우기와 같은 방식, 기획서 16절). SELECT만 쓴다.

```
cd ~/personal_project/deploy
docker run --rm -i --env-file .env mysql:8.4 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$DB_HOST" -u macros macros -t --init-command="SET SESSION TRANSACTION READ ONLY"' <<'SQL'
(아래 4절의 SQL 하나를 붙여 넣는다)
SQL
```

- 기간은 SQL의 `INTERVAL 7 DAY`를 바꾼다. 시각은 UTC로 저장되어 있어 `UTC_TIMESTAMP()`와 견준다. 한국 시간으로 보려면 `viewed_at + INTERVAL 9 HOUR`
- 기간은 "그 기간에 시작한 방문"으로 센다 (방문의 첫 줄이 기간 안). 4절의 SQL 모두 같은 기준이다
- `--init-command`로 이 접속을 읽기 전용으로 연다: 실수로 바꾸는 SQL을 붙여도 DB가 거절한다
- 배포한 뒤 한 번, 표와 인덱스가 생겼는지 본다: 위 상자에 `SHOW INDEX FROM page_views;`를 넣으면 `PRIMARY`, `uk_page_views_visit_seq`, `idx_page_views_viewed_at`이 보여야 한다

## 4. SQL

### 4.1 들어온 곳 상위

방문의 첫 줄로 정한다. 앞에서부터 처음 맞는 것: utm 값, 다른 사이트의 도메인, 이어서 본 것(`continued`: 첫 줄의 바로 앞 화면이 우리 화면 = 30분 넘게 쉬었거나 배포로 세션이 끊긴 뒤 이어서 본 것), 링크 표시, 앱 안의 브라우저, 그리고 아무것도 없으면 `direct`(주소를 치거나 즐겨찾기, 출처를 보내지 않는 링크). `continued`를 앱보다 먼저 봐야, 카카오톡으로 보다가 쉬었다 이어 본 사람을 카카오톡에서 새로 들어온 것으로 세지 않는다.

```sql
SELECT CASE
         WHEN utm_source IS NOT NULL THEN CONCAT('utm: ', utm_source)
         WHEN ref_host IS NOT NULL THEN CONCAT('site: ', ref_host)
         WHEN prev_screen IS NOT NULL THEN 'continued'
         WHEN click_id IS NOT NULL THEN CONCAT('link: ', click_id)
         WHEN app IN ('KAKAOTALK', 'INSTAGRAM', 'FACEBOOK', 'NAVER', 'DAUM', 'LINE') THEN CONCAT('app: ', app)
         ELSE 'direct'
       END AS source,
       COUNT(*) AS visits
FROM page_views
WHERE seq = 1 AND viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
GROUP BY source
ORDER BY visits DESC
LIMIT 20;
```

### 4.2 첫 화면 다음 화면

`(left)`는 첫 화면만 보고 떠난 방문.

```sql
SELECT f.screen AS first_screen, COALESCE(n.screen, '(left)') AS next_screen, COUNT(*) AS visits
FROM page_views f
LEFT JOIN page_views n ON n.visit_key = f.visit_key AND n.seq = 2
WHERE f.seq = 1 AND f.viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
GROUP BY first_screen, next_screen
ORDER BY visits DESC
LIMIT 20;
```

### 4.3 자주 가는 이동 (바로 앞 화면 → 화면)

같은 화면끼리(`FEED → FEED`)는 쪽 넘기기, `SEARCH → SEARCH`는 검색 결과의 쪽 넘기기나 다시 찾기, `POST → POST`는 댓글을 달거나 확정한 뒤 다시 본 글이 대부분이다.

```sql
WITH visits AS (
  SELECT visit_key FROM page_views
  WHERE seq = 1 AND viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
)
SELECT p.prev_screen AS from_screen, p.screen AS to_screen, COUNT(*) AS moves
FROM page_views p JOIN visits v ON v.visit_key = p.visit_key
WHERE p.prev_screen IS NOT NULL
GROUP BY p.prev_screen, p.screen
ORDER BY moves DESC
LIMIT 20;
```

### 4.4 방문당 화면 수

올리기 줄은 화면이 아니라서 세지 않는다.

```sql
WITH visits AS (
  SELECT visit_key FROM page_views
  WHERE seq = 1 AND viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
), counted AS (
  SELECT p.visit_key, SUM(p.screen <> 'UPLOAD') AS screens
  FROM page_views p JOIN visits v ON v.visit_key = p.visit_key
  GROUP BY p.visit_key
)
SELECT screens, COUNT(*) AS visits, ROUND(100 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM counted
GROUP BY screens
ORDER BY screens;
```

### 4.5 올리기까지 간 방문 비율

```sql
WITH visits AS (
  SELECT visit_key FROM page_views
  WHERE seq = 1 AND viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
)
SELECT COUNT(DISTINCT p.visit_key) AS visits,
       COUNT(DISTINCT CASE WHEN p.screen = 'POST' THEN p.visit_key END) AS saw_post,
       COUNT(DISTINCT CASE WHEN p.screen = 'POST_NEW' THEN p.visit_key END) AS opened_write,
       COUNT(DISTINCT CASE WHEN p.screen = 'UPLOAD' THEN p.visit_key END) AS uploaded,
       ROUND(100 * COUNT(DISTINCT CASE WHEN p.screen = 'UPLOAD' THEN p.visit_key END) / COUNT(DISTINCT p.visit_key), 1) AS uploaded_pct
FROM page_views p JOIN visits v ON v.visit_key = p.visit_key;
```

### 4.6 검색을 쓴 방문 (2026-11-23, JM-52)

검색을 쓴 방문 수, 검색 결과 화면 수(쪽 넘기기 포함), 검색 결과에서 연 글 수. 검색어와 결과 수는 남기지 않아서 "찾은 글이 없던 검색"은 여기서 알 수 없다.

```sql
WITH visits AS (
  SELECT visit_key FROM page_views
  WHERE seq = 1 AND viewed_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY
)
SELECT COUNT(DISTINCT p.visit_key) AS visits,
       COUNT(DISTINCT CASE WHEN p.screen = 'SEARCH' THEN p.visit_key END) AS searched,
       SUM(p.screen = 'SEARCH') AS search_pages,
       SUM(p.screen = 'POST' AND p.prev_screen = 'SEARCH') AS posts_from_search
FROM page_views p JOIN visits v ON v.visit_key = p.visit_key;
```

## 5. 한계

- **뒤로 가기**: 브라우저가 앞 화면을 저장해 두었다가 다시 보여 주면 서버에 요청이 오지 않아 줄이 없다. 그래서 순서(`seq`)만으로 이으면 "글 → 피드"가 빠질 수 있고, 바로 앞 화면(`prev_screen`)은 실제로 누른 화면을 보여 준다
- **새로고침**도 한 줄로 남는다 (구별하지 않는다)
- **바로 앞 화면과 다른 사이트의 도메인**은 브라우저가 Referer를 보낼 때만 안다. 다른 사이트로 넘어올 때 브라우저는 보통 도메인까지만 보낸다. 카카오톡 대화방의 링크는 출처를 보내지 않아 앱 종류(`KAKAOTALK`)로만 안다
- **방문 = 세션**: 30분 넘게 쉬었다 오면 새 방문이다 (4.1의 `continued`). 세션은 서버 메모리에 있어서 **배포할 때마다 모든 세션이 끊긴다**: 그때 보고 있던 방문은 둘로 나뉜다 (방문당 화면 수가 줄고, 글쓰기와 올리기가 다른 방문으로 갈 수 있다). 1주일 숫자를 남길 때 그 사이의 배포 시각을 함께 적는다
- **첫 줄이 없는 방문**: 줄이 가득 차거나 저장이 실패하면 그 줄의 순서 번호만 쓰이고 줄은 없다. 첫 줄(`seq = 1`)을 잃은 방문은 4.1, 4.2, 4.4, 4.5, 4.6에서 빠진다. 1주일 숫자를 남길 때 몇 개인지 함께 본다:

```sql
SELECT COUNT(*) AS visits_without_first_row
FROM (SELECT visit_key, MIN(seq) AS first_seq, MIN(viewed_at) AS started FROM page_views GROUP BY visit_key) v
WHERE v.first_seq > 1 AND v.started >= UTC_TIMESTAMP() - INTERVAL 7 DAY;
```

- **다시 보여 준 피드백 쓰기 화면**: 피드백 입력이 틀리면 쓰기 화면을 주소 `/feedback`으로 다시 보여 준다. 그 화면에서 넘어간 줄의 바로 앞 화면은 피드백 목록으로 보인다 (주소로는 가를 수 없다)
- **첫 화면이 에러 화면**이면 그다음 화면이 첫 줄이 된다. 그 줄의 출처는 우리 사이트로 보여 다른 사이트의 도메인을 놓친다 (방문 수도 같은 규칙으로 센다)
- **기기**: 안드로이드 태블릿은 휴대폰으로, 요즘 아이패드의 사파리는 컴퓨터로 보인다 (User-Agent가 그렇게 온다)
- **클릭**: 요청이 가는 것만 안다. 메뉴인지 몇 번째 카드인지, 답글 칸 열기처럼 요청이 없는 클릭은 다음 조각

## 6. 전후 기록

- **바꾸기 전** (nginx 기록 15일치 합계, 11-04 ~ 11-19, 사람만. 비공개 기록 저장소 `update_picture/JM-92/before-flow.txt`): 화면 조회 6,195회(피드 1쪽 50%, 글 상세 28%), 카카오톡 앱 안 19%, 인스타그램 앱 안 4%, 합계로 본 흐름 피드 3,323 → 글 상세 1,722 → 글쓰기 256 → 올리기 145. 들어온 곳은 앱 종류와 `fbclid`, utm 말고는 모르고, 방문 단위의 흐름(첫 화면, 다음 화면, 방문당 화면 수)은 모른다
- **화면마다 늘어나는 일**: 사람이 받은 화면마다 INSERT 한 줄 (요청 안의 쿼리 수는 그대로, 저장은 일꾼 스레드가 따로). 로컬에서 네 판을 함께 띄워 피드 1쪽을 500번씩 번갈아 연 결과, 사람 화면이 bot보다 늦은 정도 p50이 main +0.27 ms, JPA 저장 +8.10 ms, INSERT 한 문장 +4.64 ms, 일꾼 스레드 저장 +0.60 ms. 검토 뒤 최종 코드도 +0.33 ms (main +0.66 ms, 둘만 띄워 다시 잼). 배포 뒤 피드의 앱 쪽 p95(기준 64 ms)와 함께 [성능 문서](performance.md) 8절
- **배포하고 1주일 뒤**: 4절의 다섯 숫자를 기록 저장소 `after-flow.txt`, 이 절, Jira JM-92에 남긴다

## 7. 다음 조각

- 버튼 클릭 수: 정해 둔 버튼에만 이름표를 달고 날마다 합계로 쌓는다
- 관리자 통계에 "유입과 흐름" 카드 (4절의 숫자를 화면에서). utm 값과 도메인은 누구나 넣을 수 있는 값이라 `c:out`으로만 띄운다 (2절)
- 매주 흐름 한 줄 (스프린트를 닫을 때, 성능의 한 줄과 함께)
- 외부 도구(Umami 같은 것)와 숫자 견주기
