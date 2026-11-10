# nginx 설정을 바꿔 배포했는데 컨테이너는 옛 설정을 계속 씀 (파일 하나 bind mount의 함정)

- **날짜**: 2026-11-04 (S4-6, 로그 변경 배포 직후 발견)
- **영향**: 서비스 장애는 없음. 다만 S4-5에서 넣은 `resolver`(재배포 뒤 502 방지)와 S4-6의 IP 없는 접속 기록 형식이 **실제로는 한 번도 적용되지 않은 채** 두 번 배포됐다. 그동안 nginx 접속 기록에는 IP가 계속 찍혔다.

## 현상
PR #6(접속 기록에서 IP 제거)을 머지해 자동 배포가 성공했는데, 서버의 nginx 접속 기록에 여전히 IP가 찍혔다. `deploy.sh`의 `nginx -s reload`도 성공으로 끝났다.

컨테이너 안의 설정 파일을 보니 서버의 파일과 달랐다.

```
host file:    noip=5 resolver=1      (grep 개수)
in container: noip=0 resolver=0
inode: 25441010 (서버)  vs  25419984 (컨테이너)
```

## 원인
`compose.yaml`에서 설정을 **파일 하나**로 bind mount하고 있었다.

```yaml
- ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
```

Docker는 파일을 mount할 때 그 파일의 inode(디스크 상의 실체)를 붙잡는다. 그런데 `git pull`은 파일을 "고쳐 쓰는" 게 아니라 **새 파일을 만들어 바꿔치기**하므로 inode가 바뀐다. 컨테이너는 바꿔치기 전의 옛 파일을 계속 보고 있었고, `nginx -s reload`는 그 옛 파일을 다시 읽었을 뿐이다. (파일을 제자리에서 편집하면 반영되지만, git, 에디터 대부분은 새 파일로 바꿔치기한다)

S4-5 첫 자동 배포에서 "재배포 뒤 502 없음"을 확인했을 때 `resolver`가 동작한 줄 알았는데, 실제로는 `deploy.sh`가 app 교체 뒤 nginx를 reload해서 app 주소를 다시 찾은 덕분이었다.

## 해결
설정을 **폴더째** mount한다. 폴더 mount는 그 안에서 파일이 바뀌거나 새로 생겨도 컨테이너가 그대로 본다.

```yaml
- ./nginx:/etc/nginx/conf.d:ro        # deploy/nginx/default.conf
```

내 PC 시험용도 같은 방식(`./nginx-local:/etc/nginx/conf.d:ro`). compose의 volume 설정이 바뀌므로 이번 배포에서 nginx 컨테이너가 새로 만들어지고, 그 뒤부터는 `deploy.sh`의 reload만으로 설정 변경이 적용된다.

## 재발 방지
- 컨테이너에 넣는 설정 파일은 **파일이 아니라 폴더로 mount**한다.
- 설정을 바꾸는 배포는 "배포 성공"으로 끝내지 않고 **컨테이너 안에서 적용됐는지 확인**한다: `docker compose exec nginx nginx -T | grep <바꾼 설정>`
- 체크리스트의 "502 없음" 항목은 reload가 담당하는 것으로 정정했다. resolver는 이 PR부터 실제로 켜진다.

## 배운 것
- 배포 파이프라인이 초록이어도 설정이 적용됐다는 뜻은 아니다. 적용 여부는 실행 중인 컨테이너 안에서 봐야 한다.
- "되는 것처럼 보이는 이유"를 확인하지 않으면 다른 장치(여기서는 reload)가 가려 준 문제를 놓친다.

## 참고
- [Docker 문서: bind mount — 파일 하나를 mount할 때 inode가 바뀌면 반영되지 않는 문제](https://docs.docker.com/engine/storage/bind-mounts/)
