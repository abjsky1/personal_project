# 끌 때 AI 분석을 기다리게 넣은 설정이 DB를 먼저 닫아 분석을 실패시켰다

- **종류**: 찾은 문제 (설정). 무중단 배포(JM-62)를 만들며 내가 넣은 설정이 오히려 문제를 만들었다. 운영에 나가기 전에 PR 전 검토에서 재현해 뺐다
- **발견**: 2026-11-23, JM-62 PR 전 검토. 반박 확인을 맡은 에이전트가 내 PC에서 재현했다
- **상태**: 해결 (JM-62, PR #55에서 설정을 빼고 Spring 기본값으로)
- **영향**: 그대로 나갔다면 배포로 옛 app을 끌 때마다, 그 순간 돌던 AI 분석이 "Could not open JPA EntityManager"로 실패하고 ERROR 로그로 에러 경보가 울렸다. 그 글의 분석은 PENDING으로 남았다

## 넣은 까닭

무중단 배포에서는 옛 app을 일부러 끈다. 그 순간 뒤에서 돌던 AI 분석이 끊기지 않게 하려고 `application.properties`에 다음 두 줄을 넣었다 (커밋 `56477cd`).

```
spring.task.execution.shutdown.await-termination=true
spring.task.execution.shutdown.await-termination-period=30s
```

이름만 보면 "끌 때 일꾼의 일이 끝나기를 30초까지 기다린다"는 뜻이라 맞는 설정으로 보였다.

## 현상

내 PC에서 이 브랜치로 만든 이미지를 띄우고, Gemini 주소를 응답하지 않는 곳으로 돌려 분석이 30초쯤 걸리게 한 뒤, 분석 두 개가 도는 동안 app을 껐다 (`docker compose stop`).

| | 이 설정을 넣은 이미지 | 기본값 이미지 (`cb41f79`) |
|---|---|---|
| DB 연결 묶음(Hikari) | 끄자마자 닫힘 | 하던 분석이 끝난 뒤에 닫힘 |
| 하던 분석 둘 | 둘 다 실패: "Could not open JPA EntityManager" (ERROR, 에러 경보에 걸린다) | 결과(실패, NETWORK)를 저장하고 끝남 |
| 그 글의 분석 상태 | PENDING으로 남음 | FAILED로 저장 |
| 끄는 데 걸린 시간 | 27.3초 | 하던 일이 끝나는 대로 (종료 코드 143, 정상 종료) |

같은 검토에서, 받던 요청도 오래 걸리면 끄는 데 40초(그때의 `stop_grace_period`)를 넘겨 Docker가 강제로 끄는 것(종료 코드 137)도 재현했다: 사진을 아주 느리게 보내는 요청 하나와 분석 네 개를 걸어 두자 40.8초에 강제 종료되고, 글 둘의 분석이 PENDING으로 남았다.

## 원인

Spring 7(`ExecutorConfigurationSupport`)은 끌 때 두 단계가 있다.

1. **끄는 단계(lifecycle stop)**: Tomcat이 받던 요청을 마치고(최대 30초, `spring.lifecycle.timeout-per-shutdown-phase`), 기본값의 일꾼(`ThreadPoolTaskExecutor`)은 이 단계에서 하던 일이 끝나기를 기다린다. 이때는 DB 연결이 아직 열려 있다
2. **치우는 단계(destroy)**: 빈을 하나씩 없앤다. JPA(EntityManagerFactory)와 DB 연결 묶음(Hikari)이 이때 닫힌다

`await-termination=true`를 켜면 일꾼은 1에서 기다리지 않고(늦게 끄기, `lateShutdown`) 2에서 기다린다. 그런데 2에서는 JPA와 DB가 일꾼보다 먼저 닫혀서, 기다리는 동안 분석이 결과를 저장하려 하면 DB를 열 수 없다. 기다림이 오히려 실패를 만든 것이다. 또 1의 Tomcat 30초와 2의 기다림 30초가 차례로 더해져 40초를 넘길 수 있었다.

## 해결

- 두 줄을 뺐다. 기본값이 이미 "DB를 연 채로 하던 분석을 기다리기"를 한다. 왜 넣지 않는지는 `application.properties`에 주석으로 남겼다
- `stop_grace_period`를 40초에서 60초로: 끄는 단계의 Tomcat(최대 30초)과 하던 분석(한 번에 최대 35초: 연결 5초 + 읽기 30초)이 겹쳐도 강제 종료되지 않게. 하던 일이 없으면 1초 안에 꺼진다
- 남은 것: 줄에서 아직 시작하지 않은 분석은 기본값에서도 버려진다 → [2026-11-23-pending-analysis-left-on-shutdown](2026-11-23-pending-analysis-left-on-shutdown.md)

## 재발 방지

- 끄는 동작을 바꾸는 설정은 "도는 일이 있을 때 끄기"를 재현해서 로그와 DB를 본 뒤에 넣는다 (내 PC: Gemini 주소를 응답 없는 곳으로 돌려 분석을 오래 걸리게 하고 `docker compose stop`)
- PR 전 검토에 반박 확인(직접 재현)을 둔다: 이번 것은 코드만 읽어서는 맞아 보였고, 재현에서 드러났다

## 배운 것

- 설정 이름("기다린다")과 실제 순서(무엇이 먼저 닫히나)는 다를 수 있다. 끄는 순서는 프레임워크 판마다 바뀐다 (Spring 6.1부터 일꾼이 끄는 단계에 참여한다)
- "안전하게 하려고 더한 설정"도 전후를 재야 한다: 기본값이 이미 더 나았다

## 기록

- 비공개 기록 저장소 `update_picture/JM-62/draft3-review-and-rehearsal.txt`
- Jira JM-62 댓글 10095
