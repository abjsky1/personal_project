# 트랜잭션마다 설정 문장이 다섯 번 더 간다

- **종류**: 찾은 문제 (측정, 성능)
- **발견**: 2026-11-20, JM-92. 사용자 흐름의 저장 시간을 재다가, 로컬 MySQL의 general log(받은 문장을 모두 적는 기록)를 잠깐 켜서 봤을 때
- **상태**: 할 일 (운영에서 왕복 시간을 먼저 잰다)
- **영향**: 화면마다 DB 왕복이 다섯 번쯤 더 든다. 로컬에서는 문장 사이가 0.7 ~ 1.3 ms라 화면마다 3 ~ 5 ms쯤이다. 운영(EC2와 RDS)의 왕복 시간은 아직 재지 않았다

## 현상

로컬 서버(코드 `3d25b74`에 JM-92를 더한 것)로 피드 1쪽을 한 번 열었을 때 DB가 받은 문장 (11-20 15:01):

| 순서 | 문장 | 무엇 |
|---|---|---|
| 1 | `SET SESSION TRANSACTION READ ONLY` | 읽기 전용 트랜잭션 시작 |
| 2 | `SET autocommit=0` | |
| 3 ~ 6 | `select` 네 개 | 글 24개, 전체 글 수, 분석, 댓글 수 (실제로 필요한 것) |
| 7 | `COMMIT` | |
| 8 | `SET autocommit=1` | 연결을 풀에 돌려주기 전에 되돌림 |
| 9 | `SET SESSION TRANSACTION READ WRITE` | |

- 9문장 가운데 5문장이 트랜잭션 설정이다. 공지 목록은 6문장 가운데 5문장, 피드백 목록은 7문장 가운데 5문장
- 쓰기 트랜잭션(JPA로 한 줄 저장)은 `SET autocommit=0`, INSERT, `COMMIT`, `SET autocommit=1`로 4문장이다. 사용자 흐름의 저장은 그래서 JPA 대신 INSERT 한 문장으로 바꿨다 (아래 기록)

## 원인

Spring의 `@Transactional(readOnly = true)`가 트랜잭션을 열 때 DB 연결에 "읽기 전용"과 "자동 커밋 끔"을 걸고, 끝나면 되돌린다. MySQL 드라이버(Connector/J)는 기본 설정에서 이것을 그때마다 문장으로 서버에 보낸다 (`readOnlyPropagatesToServer=true`, HikariCP의 `autoCommit=true`).

## 앞으로 해결해 나갈 것

1. 운영에서 왕복 시간을 잰다: 앱 쪽 시간(CloudWatch 요청 줄)과 RDS의 지표로, 화면마다 다섯 번이 몇 ms인지 먼저 본다. 크지 않으면 여기서 멈춘다
2. 줄일 때의 후보 두 가지 (하나씩 바꾸고 하나씩 잰다)
   - 드라이버 설정 `readOnlyPropagatesToServer=false`: `SET SESSION TRANSACTION READ ONLY/READ WRITE` 두 문장이 빠진다. 읽기 전용 트랜잭션에 대한 MySQL 쪽 최적화는 잃는다
   - HikariCP `auto-commit=false`와 Hibernate `connection.provider_disables_autocommit=true`: `SET autocommit` 두 문장이 빠진다. **주의**: 트랜잭션 밖에서 쓰는 SQL이 커밋되지 않게 된다. 사용자 흐름의 저장(`PageFlowRecorder`, 트랜잭션 없이 INSERT 한 문장)을 먼저 트랜잭션 안으로 옮겨야 한다
3. 목표: 피드 1쪽 9문장 → 5문장. 앱 쪽 피드 p50, p95가 줄어드는지 본다 (기준: CloudWatch 11-04 ~ 11-19 피드 p95 64 ms)

## 재는 법

- 로컬: MySQL에서 `SET GLOBAL general_log=ON`, 화면을 한 번 열고 `OFF`. 로그의 `Query` 줄을 센다 (root로, 내 PC에서만)
- 운영: [performance.md](../performance.md) 4.1의 앱 쪽 시간. 바꾼 배포 전후 같은 길이의 기간으로 비교한다

## 기록

- [performance.md](../performance.md) 6절 후보, 8절 JM-92
- 비공개 기록 저장소 `update_picture/JM-92/draft1-perf.txt`
