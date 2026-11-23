# 서버를 끌 때 줄에서 기다리던 AI 분석이 버려져 PENDING으로 남는다

- **종류**: 찾은 문제 (운영, 데이터). 전부터 있던 것을 무중단 배포(JM-62)의 PR 전 검토에서 찾았다
- **발견**: 2026-11-23, JM-62 PR 전 검토 (끄는 순서를 재현하다가). 같은 날 내 PC에서 숫자로 재현했다
- **상태**: 해결 (JM-110, PR #56). 내 PC 재현으로 확인했고, 운영에서의 확인은 배포 뒤 "기록"에 더한다
- **영향**: 배포(또는 재시작) 순간 분석 줄에서 기다리던 글은 화면에 계속 "분석 중"으로 남는다. 칼로리가 끝내 나오지 않고, 그 글은 통계에서도 빠진다. 줄이 가득 찼을 때(51번째부터) 올라온 글도 같은 상태로 남는다. 다시 넣는 코드가 없어서 저절로 풀리지 않는다

## 현상

내 PC에서 운영과 같은 이미지(`cb41f79`)로 재현했다 (2026-11-23 13:13). AI 서버 주소를 같은 Docker 망의 "연결만 받고 아무것도 보내지 않는 서버"로 돌려 분석 한 번이 읽기 제한(30초)까지 걸리게 했다.

| 때 | 분석 상태 |
|---|---|
| 글 6개를 3초 안에 올린 직후 | PENDING 6 (일꾼 2명이 둘을 분석 중, 넷은 줄에서 기다림) |
| app을 끈 직후 (31초 걸림, 정상 종료 143) | FAILED 2 (하던 둘은 시간 초과로 끝까지 저장됨), **PENDING 4** |
| 다시 켜고 3분 뒤 | FAILED 2, **PENDING 4** (그대로) |

## 원인

- 글을 올리면 분석 한 줄(`food_analyses`)을 PENDING으로 만들고, 분석을 일꾼 줄에 넣는다 (`PostService.startAnalysis` → `FoodAnalysisService.analyzeInBackground`, 일꾼 2명, 줄 50)
- 줄은 app의 메모리에만 있다. app을 끄면 Spring은 하던 일(일꾼 둘)은 끝나기를 기다리지만 줄에 남은 일은 버린다. 줄이 가득 차서 넣지 못한 글도 PENDING으로 남는다 (`TaskRejectedException`을 잡고 넘어감)
- PENDING을 다시 줄에 넣는 코드가 없다 (기획서 6.1절: 자동 재시도는 미뤄 둠)
- 예전 배포는 app을 10초 만에 강제로 꺼서 하던 일까지 PENDING으로 남을 수 있었다. 무중단 배포에서 `stop_grace_period`를 60초로 늘려 하던 일은 지키지만, 줄의 일은 여전히 버려진다

## 해결 (JM-110)

1. **분석을 시작할 때 그 글을 맡는다**: `food_analyses`에 `claimed_at` 칸(비워 둘 수 있음)을 더했다. 일꾼이 분석을 시작할 때 "PENDING이고, 아무도 맡지 않았거나 맡은 지 3분이 넘은" 글일 때만 시각을 적는 UPDATE 한 번(`FoodAnalysisRepository.claimAnalysis`)으로 맡는다. 맡지 못하면 아무것도 하지 않는다. 같은 글이 두 줄에 들어가도 AI는 한 번만 부른다 (직접 적기의 `claimTyped`와 같은 방식)
2. **5분마다 다시 넣는다**: `PendingAnalysisRequeuer`가 켜진 지 2분 뒤부터 5분마다, 만든 지 3분이 넘은 PENDING 가운데 아무도 맡지 않았거나 맡은 지 3분이 넘은 것을(글이 지워지지 않았으면) 오래된 것부터 한 번에 10개까지 다시 줄에 넣는다. 줄이 가득 차면 멈추고 다음 차례로 미룬다. 줄이 가득 차서 남은 글도 이것으로 풀린다
3. 켤 때 한 번만 찾지 않는다: 무중단 배포에서는 새 app이 먼저 켜지고, 옛 app이 1분쯤 뒤에 꺼지면서 줄의 일을 버린다
4. "맡은 지 3분"인 까닭: 분석 한 번은 길어야 35초다(AI 연결 5초 + 읽기 30초). 끌 때 Spring이 기다리는 30초가 지나면 하던 분석이 끊기는데, 그 글은 맡은 채로 PENDING이 된다. 처음에는 10분으로 했다가, 아래 재현에서 그런 글이 3분 안에 풀리지 않아 3분으로 줄였다
5. 하루 분석 한도는 분석이 시작될 때 그대로 검사한다 (넘으면 LIMIT_EXCEEDED)

| 내 PC 재현 (위와 같은 방법, 다시 넣기만 시험용으로 20초마다) | 바꾸기 전 | 고친 뒤 |
|---|---|---|
| 끄고 다시 켜기: 다시 켜고 3분 뒤 | PENDING 4 그대로 | 20초 뒤 맡은 적 없는 둘, 3분 뒤 끊긴 둘이 다시 들어가 **PENDING 0** |
| 무중단 배포(`bash deploy.sh current`)로 옛 app이 줄을 버림 | (같은 문제) | 배포가 끝나고 1초 뒤 **PENDING 0** |
| 같은 글을 AI가 부른 횟수 | | 6개 모두 1번. 옛 app은 같은 글을 네 번 꺼냈지만 모두 "already claimed"로 건너뜀 |

테스트: 맡기는 한 번만, 맡은 지 오래된 PENDING은 다시 맡기, 끝난 분석은 못 맡기, 다시 넣을 분석 고르기(오래됨, 맡지 않음 또는 오래 맡음, 지워진 글 빼기, 끝난 분석 빼기, 순서와 개수)는 실제 DB로(`FoodAnalysisRepositoryTest`), 맡지 못하면 AI를 부르지 않기는 `FoodAnalysisServiceTest`, 다시 넣기와 줄이 찼을 때 멈추기는 `PendingAnalysisRequeuerTest`. 전체 361개 통과.

## 재발 방지

- 새 기능이 일꾼 줄에 일을 넣을 때는 "줄은 메모리에만 있다"를 전제로, 끝나지 못한 일을 DB 상태로 다시 찾을 수 있게 한다
- 운영에서 PENDING이 오래 남는지는 다시 넣기 로그(`Pending analyses requeued: found=..., queued=...`)와 아래 SQL로 본다

## 재는 법

- 내 PC: 위 재현 (기록 저장소 `update_picture/JM-110/tools/before_pending.sh`, `after_pending.sh`)
- 운영: 서버에서 `food_analyses`의 PENDING 수와 가장 오래된 것의 시각 (기획서 16절의 SQL 방식)

## 기록

- [2026-11-23-await-termination-closed-db-first](2026-11-23-await-termination-closed-db-first.md) (끄는 순서)
- 비공개 기록 저장소 `update_picture/JM-110/` (`before-behavior.txt`, `draft1-behavior.txt`)
- Jira JM-110, JM-62 댓글 10095
