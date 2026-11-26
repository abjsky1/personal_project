# app 기록이 CloudWatch를 기다리는 방식(blocking)이라, CloudWatch에 닿지 않으면 app이 켜지지 못한다

- **종류**: 찾은 문제 (점검, 작음). 운영에서 일어난 적은 없다
- **발견**: 2026-11-26, JM-91에서 nginx 접속 기록을 CloudWatch로 보내는 설정을 쓰다가. Docker의 awslogs 소스와 문서를 읽고 내 PC에서 재현했다
- **상태**: 해결 (2026-11-26 같은 날, JM-91 PR #62). app도 nginx와 같은 기록 설정 하나를 쓴다
- **영향**: CloudWatch Logs에 닿지 않을 때만 생긴다 (AWS 장애, 서버의 권한이나 네트워크 문제). 배포 때 새 자리가 켜지지 못하면 `deploy.sh`가 그 자리만 끄고 멈춰서 사이트는 옛 자리가 그대로 맡는다 (배포만 실패). 서버를 다시 켤 때(재부팅)라면 app이 켜지지 못해 사이트가 502가 된다

## 현상

| 무엇 | 값 |
|---|---|
| app의 기록 방식 (PR #62 전) | `awslogs`, `mode`를 적지 않아 기본값 blocking (`deploy/compose.yaml`의 `x-logging-cloud`) |
| nginx의 기록 방식 | 같은 awslogs에 `mode: non-blocking`, `max-buffer-size: 4m` (2026-11-26, JM-91) |
| 서버의 Docker | 25.0.18 |
| 내 PC 재현 (Docker 29.9.2, 닿지 않는 CloudWatch 주소 `awslogs-endpoint=https://127.0.0.1:9`, 2026-11-26) | blocking: `failed to initialize logging driver: failed to create Cloudwatch log stream`으로 컨테이너가 켜지지 않음 (종료 코드 125). non-blocking: 켜져서 명령이 돌고 끝남 (종료 코드 0) |
| app이 찍는 로그 줄 | 시간당 약 160 ~ 220줄 (11-24 ~ 11-26 서버의 두 자리) |

## 원인

Docker의 awslogs 드라이버는 기본값(blocking)이면 컨테이너를 켤 때 CloudWatch에 로그 스트림을 만들고, 실패하면 그 오류로 컨테이너를 켜지 않는다. non-blocking이면 스트림 만들기를 뒤에서 다시 시도하고(1초부터 32초까지 늘려 가며) 컨테이너는 먼저 켠다. 서버와 같은 25 버전대의 소스와 지금 소스가 같다.

켜진 뒤에도 blocking에서는 드라이버가 모아 두는 줄(기본 4,096줄)이 차면 app이 로그를 쓰는 곳에서 기다린다. 다만 지금 양(시간당 약 200줄)으로는 거의 차지 않아서, 걱정할 것은 주로 켤 때다.

app은 2026-11-04(S4-6)부터 기본값으로 보냈다. 그때는 CloudWatch에 닿지 않는 경우를 따로 생각하지 않았다.

## 해결

1. app의 `x-logging-cloud`에도 `mode: non-blocking`, `max-buffer-size: 4m`을 더했다 (PR #62). nginx의 묶음(`x-logging-cloud-nginx`)과 같아져서 하나로 합쳤다. nginx의 설정 값은 그대로라 이 배포에서 nginx는 다시 만들어지지 않는다 (`docker compose config --hash`로 app1, app2만 바뀐 것을 확인). 무중단 배포라 바꿀 때 끊기지 않는다
2. 대신 잃는 것: CloudWatch에 닿지 않는 동안 메모리 4 MB를 넘친 줄은 버려진다. 그동안은 ERROR 줄이 남아도 경보(CloudWatch 안에서 센다)가 어차피 울릴 수 없으므로 잃는 것이 크지 않다. 사이트가 멈추면 UptimeRobot(밖에서 5분마다)이 알린다
3. 목표: CloudWatch에 닿지 않아도 새 app이 켜지고(배포 성공, 재부팅 뒤 사이트 200), 화면 응답 시간은 그대로
4. 바꾸며 따진 것: non-blocking이어도 켜질 때 스트림을 끝내 만들지 못하면, Docker 25는 못 보낸 줄이 4,096줄(app은 지금 양으로 20시간쯤) 쌓인 뒤 그 컨테이너를 끌 때 끝나지 않는다 (nginx를 바꿀 때 PR 전 검토로 찾음, moby v25.0.5). 배포 때 옛 자리를 끄는 경우라면 사이트는 새 자리가 맡아 배포 작업만 끝나지 않는다. 하지만 사이트를 맡은 자리가 예기치 않게 꺼지면(메모리 초과 등) Docker가 정리를 끝내지 못해 다시 켜지 않으므로 502가 되고, Docker 데몬을 다시 켜야 풀린다 (PR 전 검토, moby v25.0.5의 handleContainerExit). 스트림을 만들지 않게 하는 `awslogs-create-stream: "false"`는 서버의 Docker 25가 받지 않아 쓰지 않았다 (moby v25.0.5의 옵션 검사에 이 이름이 빠져 있어 컨테이너를 만들 때 거절된다). 켜질 때 스트림을 만들면 이 일은 없고, 오늘까지 세 스트림(macros-app1-1, macros-app2-1, macros-nginx-1) 모두 만들어졌다. 배포 뒤 확인하는 명령은 [performance.md](../performance.md) 5절
5. 확인 (내 PC): 기본값은 CloudWatch에 닿지 않을 때 컨테이너가 켜지지 않고(종료 코드 125), 바꾼 설정은 켜진다 (위 현상의 재현). 운영 배포 뒤 확인은 기록 저장소와 Jira JM-91에 남긴다

## 재는 법

설정은 서버에서 본다 (사이트를 맡은 자리. `cat nginx/active-app.inc`로 본다):

```
docker inspect --format '{{.HostConfig.LogConfig.Type}} {{json .HostConfig.LogConfig.Config}}' macros-app2-1
```

재현은 내 PC의 Docker Desktop에서 한다. CloudWatch에 닿지 않는 주소로 보내므로 AWS에는 아무것도 만들어지지 않는다:

```
docker run --rm --log-driver awslogs --log-opt awslogs-region=ap-northeast-2 --log-opt awslogs-group=/macros/local-test --log-opt awslogs-endpoint=https://127.0.0.1:9 alpine echo ran
```

이 명령은 켜지지 않고, 끝에 `--log-opt mode=non-blocking --log-opt max-buffer-size=4m`을 더하면(`alpine` 앞에) 켜진다.

## 기록

- 설정과 이유: `deploy/compose.yaml`의 `x-logging-cloud` (app과 nginx가 같이 쓴다), [prevent-observability](prevent-observability.md) 10번
- 재현 출력과 서버의 로그 줄 수: 비공개 기록 저장소 `update_picture/JM-91/before-nginx-cloudwatch-2026-11-26.txt`
