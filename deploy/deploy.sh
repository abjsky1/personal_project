#!/bin/bash
# 서버에서 app을 새 이미지로 바꾸는 스크립트 (무중단 배포, JM-62). 평소에는 GitHub Actions가 SSM으로 실행한다 (ci.yml의 deploy job).
# 사람이 직접 실행해도 된다 (deploy 폴더에서):
#   bash deploy.sh <커밋 SHA>          ← 그 커밋으로 만든 이미지로 바꾼다 (앞 7자리만 줘도 된다)
#   bash deploy.sh <예전 커밋 SHA>     ← 되돌리기(롤백). 이미지는 GHCR에 남아 있으므로 다시 받기만 하면 된다. 되돌릴 때도 끊기지 않는다
#   bash deploy.sh current             ← 지금 사이트를 맡은 버전을 그대로 다시 켠다. .env를 바꾼 뒤 적용할 때 쓴다 (끊기지 않는다)
#
# app은 app1, app2 두 자리가 있고 평소에는 한쪽만 켜져 있다 (compose.yaml). 바꾸는 순서:
#   1. 지금 사이트를 맡은 자리를 알아낸다 (nginx/active-app.inc. 그 자리가 꺼져 있거나 파일이 없으면 켜져 있는 컨테이너로)
#   2. 다른 자리를 새 이미지로 켠다 (그동안 사용자는 계속 옛 자리를 쓴다)
#   3. 새 자리가 요청을 받을 수 있을 때까지 기다린다. 끝내 안 되면 새 자리만 끄고 멈춘다 (사이트는 옛 자리 그대로)
#   4. 데운다: 화면마다 한 번씩 미리 불러, 배포 뒤 첫 사용자가 JSP 컴파일을 기다리지 않게 한다. 첫 화면이 안 열리면 멈춘다
#   5. nginx가 보는 자리를 바꾸고 reload한다 (reload는 하던 연결을 끊지 않는다)
#   6. 옛 nginx 일꾼이 하던 요청(느린 사진 올리기 포함)을 다 마칠 때까지 기다리고, 새 자리를 한 번 더 확인한 뒤 옛 자리를 끈다
#      (새 자리가 그새 죽었으면 옛 자리로 되돌린다)
#   7. nginx, certbot의 compose 설정이 바뀌었으면 적용한다 (안 바뀌었으면 아무것도 하지 않는다)
#
# 비상시(GHCR 장애이고 서버에도 이미지가 없을 때)도 끊김 없이: 서버에서 이미지를 만들고 그 태그로 이 스크립트를 부른다
#   APP1_TAG=local-<SHA> docker compose build app1 && bash deploy.sh local-<SHA>
#   두 자리는 같은 이미지 이름을 쓰므로 app1으로 만든 이미지를 어느 자리에든 켤 수 있다. 빌드하는 1~2분 동안 서버 메모리를 많이 쓴다
#
# 내 PC 리허설용 값 (서버에서는 쓰지 않는다):
#   NGINX_DIR=nginx-local      nginx 설정 폴더 (compose.local.yaml이 nginx-local을 붙인다)
#   DEPLOY_PRUNE=0             쓰지 않는 이미지를 지우지 않는다 (내 PC의 다른 프로젝트 이미지까지 지우지 않게)
#   DEPLOY_SERVICES=nginx      7단계에서 맞출 서비스 (내 PC에는 certbot이 없다)

# -e: 명령이 하나라도 실패하면 바로 멈춘다 / -u: 없는 변수를 쓰면 오류 / -o pipefail: 파이프 중간의 실패도 실패로
set -euo pipefail
# 이 파일이 있는 폴더(deploy)로 이동 → 어디서 실행해도 compose.yaml, .env를 찾는다
cd "$(dirname "$0")"

ARG="${1:?사용법: bash deploy.sh <커밋 SHA> 또는 bash deploy.sh current}"
NGINX_DIR="${NGINX_DIR:-nginx}"
# nginx가 지금 보는 자리를 적어 두는 파일. nginx 설정 폴더 안에 있어서 nginx 컨테이너도 /etc/nginx/conf.d/active-app.inc로 본다
ACTIVE_FILE="$NGINX_DIR/active-app.inc"
# 옛 구성(app 한 자리)일 때의 컨테이너 이름. 이 방식으로 처음 배포할 때 한 번만 쓰인다
LEGACY_CONTAINER=macros-app-1
# 데우기, 확인 요청의 User-Agent. bot이 들어 있어 방문 수, 사용자 흐름, 위치 통계에 세지 않는다 (Visitors.java)
UA="jjikmuk-deploy-bot"
# 옛 nginx 일꾼이 하던 요청을 마치기를 기다리는 최대 시간(초)
DRAIN_MAX_SECONDS=60

log() { echo "[deploy] $*"; }
warn() { echo "[deploy] 경고: $*"; }

# ── 도우미 ──
# 자리(app1, app2)의 컨테이너 이름: compose 구성 이름(macros) + 서비스 + 번호
container() { echo "macros-$1-1"; }
is_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]; }
# 컨테이너가 쓰는 이미지의 태그 (ghcr.io/abjsky1/macros-app:<태그>의 <태그>)
image_tag() { docker inspect -f '{{.Config.Image}}' "$1" 2>/dev/null | sed 's/.*://' || true; }
legacy_exists() {
  local names
  names=$(docker ps -a --format '{{.Names}}')
  grep -qx "$LEGACY_CONTAINER" <<< "$names"
}
# app1 → APP1_TAG
tag_var() { echo "$(echo "$1" | tr '[:lower:]' '[:upper:]')_TAG"; }
# active-app.inc를 임시 파일로 쓴 뒤 한 번에 바꾼다 (쓰는 도중의 빈 파일을 nginx가 읽지 않게. .tmp는 *.inc에 맞지 않는다)
write_active() {
  echo "set \$app http://$1:8080;" > "$ACTIVE_FILE.tmp"
  mv "$ACTIVE_FILE.tmp" "$ACTIVE_FILE"
}
# .env의 한 줄을 바꾸거나 더한다. 마지막 줄에 줄바꿈이 없으면 먼저 넣는다 (안 그러면 앞 줄 끝에 붙어 그 값이 깨진다)
set_env() {
  if grep -q "^$1=" .env; then
    sed -i "s/^$1=.*/$1=$2/" .env
  else
    if [ -s .env ] && [ -n "$(tail -c1 .env)" ]; then echo >> .env; fi
    echo "$1=$2" >> .env
  fi
}
# nginx 컨테이너 안에서 wget (busybox). -T: 한 번에 기다리는 최대 초 (없으면 멈춘 app에 오래 매달릴 수 있다)
nginx_wget() { docker compose exec -T nginx wget -q -U "$UA" "$@" 2>/dev/null; }

# ── 0. 시작 전 확인 ──
# 한 번에 하나만: CI 배포 중에 손으로 되돌리기를 하는 경우처럼 두 배포가 겹치지 않게 (flock이 있을 때)
if command -v flock >/dev/null 2>&1; then
  exec 9>"/tmp/macros-deploy.lock"
  if ! flock -n 9; then
    log "멈춤: 다른 배포가 돌고 있다. 끝난 뒤 다시 실행한다"
    exit 1
  fi
fi
# nginx가 꺼져 있으면 새 자리를 확인할 수도, 바꿔치기할 수도 없다
if ! docker compose exec -T nginx true >/dev/null 2>&1; then
  log "멈춤: nginx 컨테이너가 꺼져 있다. 먼저 docker compose up -d nginx"
  exit 1
fi

# ── 1. 지금 사이트를 맡은 자리 ──
FILE_SLOT=""
if [ -f "$ACTIVE_FILE" ]; then
  FILE_SLOT=$(grep -o 'app[12]' "$ACTIVE_FILE" | head -1 || true)
fi
CURRENT=""
if [ -n "$FILE_SLOT" ] && is_running "$(container "$FILE_SLOT")"; then
  CURRENT="$FILE_SLOT"
elif [ -z "$FILE_SLOT" ] && is_running "$(container app1)"; then
  # 파일이 없거나 비었으면 nginx는 default.conf의 기본값 app1을 본다
  CURRENT=app1
elif is_running "$(container app2)"; then
  CURRENT=app2
elif is_running "$(container app1)"; then
  CURRENT=app1
fi
if [ -n "$CURRENT" ] && [ "$CURRENT" != "${FILE_SLOT:-app1}" ]; then
  warn "active-app.inc(${FILE_SLOT:-없음})와 실제로 켜져 있는 자리가 다르다. 켜져 있는 $CURRENT를 지금 자리로 본다"
fi
# 이 방식으로 처음 배포할 때: 사이트는 아직 옛 구성의 app이 맡고 있다.
# git pull로 바뀐 default.conf는 active-app.inc가 없으면 app1을 보므로, 옛 app을 먼저 파일에 적어 둔다.
# (안 그러면 아래에서 배포가 실패한 채로 nginx가 6시간마다 하는 reload를 만나, 꺼진 app1을 보게 되어 사이트가 502가 된다)
LEGACY=0
if [ -z "$CURRENT" ] && is_running "$LEGACY_CONTAINER"; then
  LEGACY=1
  write_active app
fi
if [ "$CURRENT" = "app1" ]; then
  NEXT=app2
else
  NEXT=app1
fi
if [ -n "$CURRENT" ]; then
  CURRENT_LABEL="$CURRENT"
elif [ "$LEGACY" = 1 ]; then
  CURRENT_LABEL="옛 구성의 app"
else
  CURRENT_LABEL="없음"
fi

# ── 켤 버전 ──
REDEPLOY=0
if [ "$ARG" = "current" ]; then
  # 지금 사이트를 맡은 컨테이너의 이미지 태그를 그대로 쓴다 (.env를 바꾼 뒤 그 값으로 다시 켜기)
  REDEPLOY=1
  if [ -n "$CURRENT" ]; then
    TAG=$(image_tag "$(container "$CURRENT")")
  elif [ "$LEGACY" = 1 ]; then
    TAG=$(image_tag "$LEGACY_CONTAINER")
  else
    log "멈춤: 켜져 있는 app이 없어서 current가 어느 버전인지 알 수 없다. SHA를 직접 준다"
    exit 1
  fi
else
  TAG="$ARG"
  # 짧은 SHA(7~39자리)는 저장소에서 40자리로 늘린다 (GHCR의 태그는 40자리)
  if [[ "$TAG" =~ ^[0-9a-f]{7,39}$ ]]; then
    FULL=$(git rev-parse --verify --quiet "$TAG^{commit}" 2>/dev/null || true)
    if [ -n "$FULL" ]; then TAG="$FULL"; fi
  fi
fi
# .env에 sed로 적으므로 /, & 같은 글자가 들어가면 안 된다
if ! [[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
  log "멈춤: 태그 모양이 이상하다 ($TAG)"
  exit 1
fi
# 같은 버전이 이미 사이트를 맡고 있으면 할 일이 없다 (CI를 다시 돌린 경우 등. 두 자리가 같은 버전이 되면 바로 앞 버전의 이미지를 잃는다)
if [ "$REDEPLOY" = 0 ] && [ -n "$CURRENT" ] && [ "$(image_tag "$(container "$CURRENT")")" = "$TAG" ]; then
  log "이미 $CURRENT가 이 버전으로 사이트를 맡고 있다. 할 일 없음 (같은 버전을 다시 켜려면 bash deploy.sh current)"
  exit 0
fi
log "지금 사이트: $CURRENT_LABEL → 새 버전을 켤 자리: $NEXT ($TAG)"

# 바꿔치기 전에 실패하면: 새 자리만 끄고 멈춘다. 사이트는 옛 자리가 그대로 맡는다
fail_before_switch() {
  log "실패: $1. 사이트는 $CURRENT_LABEL가 그대로 맡는다. $NEXT의 최근 로그:"
  docker compose logs "$NEXT" --tail 30 || true
  docker compose stop "$NEXT" || true
  exit 1
}

# ── 2. 새 자리를 새 이미지로 켠다 ──
# 그 자리의 태그 변수(APP1_TAG 또는 APP2_TAG)를 이 스크립트 안에서만 먼저 바꾼다. compose는 .env보다 이 값을 먼저 쓴다.
# .env에는 바꿔치기가 끝나고 새 자리를 다시 확인한 뒤에 적는다 → 배포가 실패해도 .env는 실제로 배포된 버전 그대로 남는다
TAG_VAR=$(tag_var "$NEXT")
export "$TAG_VAR=$TAG"

log "이미지 받기"
# --ignore-pull-failures: GHCR이 잠깐 안 될 때도, 서버에 이미 있는 이미지(바로 앞 버전, 서버에서 만든 local- 이미지)로는 켤 수 있게 받기 실패를 넘긴다.
# 서버에도 없는 이미지면 아래 up이 다시 받으려다 실패해서 여기서 멈춘다 (사이트는 옛 자리 그대로)
docker compose pull --quiet --ignore-pull-failures "$NEXT"
# --no-deps: 이 자리만 켠다 (서비스 이름을 붙였으므로 다른 자리와 nginx는 그대로)
# --no-build: 이미지를 받지 못해도 서버에서 소스로 빌드하지 않는다. compose.yaml에 비상용 build 줄이 있어서, 이것이 없으면
#   없는 태그를 받으려다 실패한 뒤 서버(메모리 2GB)에서 빌드를 시작해 버린다 (2026-11-23 내 PC 리허설에서 찾음)
docker compose up -d --no-deps --no-build "$NEXT"

# ── 3. 새 자리가 요청을 받을 수 있을 때까지 (최대 2분) ──
# nginx 컨테이너에서 이름으로 불러 본다: nginx가 실제로 그 자리를 찾아갈 수 있는지까지 같이 확인된다.
# (compose.yaml의 healthcheck는 30초마다라서 기다리지 않고, 2초마다 직접 묻는다)
log "$NEXT가 뜰 때까지 기다림"
READY=0
# 횟수가 아니라 시각으로 끊는다 (묻는 데도 1초쯤 걸려서, 횟수로 세면 2분이 3분이 된다)
DEADLINE=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if nginx_wget -O /dev/null -T 5 "http://$NEXT:8080/actuator/health"; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" != 1 ]; then
  fail_before_switch "2분이 지나도 $NEXT가 응답하지 않는다"
fi

# ── 4. 데우기 ──
# 화면(JSP)은 처음 불릴 때 컴파일된다. 사용자를 넘기기 전에 화면마다 한 번씩 불러 둔다.
# 첫 화면이 안 열리면(health는 정상이어도 화면이 깨졌을 수 있다) 넘기지 않고 멈춘다. 나머지 화면은 실패해도 넘어간다
warm() {
  local start end
  start=$(date +%s%N)
  if nginx_wget -O /dev/null -T 30 "http://$NEXT:8080$1"; then
    end=$(date +%s%N)
    log "데우기 $1 $(( (end - start) / 1000000 ))ms"
    return 0
  fi
  log "데우기 실패: $1"
  return 1
}
# 목록 화면에서 상세 화면의 번호 하나를 찾는다 (글, 공지, 피드백). 없으면 빈 값
first_id() {
  nginx_wget -O - -T 30 "http://$NEXT:8080$1" | grep -o "href=\"$2/[0-9]*\"" | head -1 | grep -o '[0-9]*' || true
}
if ! warm /; then
  fail_before_switch "새 버전의 첫 화면이 열리지 않는다"
fi
for page in /posts/new /notice /feedback /feedback/new /terms /privacy; do
  warm "$page" || true
done
POST_ID=$(first_id / /posts)
NOTICE_ID=$(first_id /notice /notice)
FEEDBACK_ID=$(first_id /feedback /feedback)
if [ -n "$POST_ID" ]; then warm "/posts/$POST_ID" || true; fi
if [ -n "$NOTICE_ID" ]; then warm "/notice/$NOTICE_ID" || true; fi
if [ -n "$FEEDBACK_ID" ]; then warm "/feedback/$FEEDBACK_ID" || true; fi

# ── 5. nginx가 보는 자리를 바꾼다 ──
# 바꾸기 전 파일을 남겨 두고, 검사나 reload가 실패하면 되돌린 뒤 새 자리를 끄고 멈춘다
rm -f "$ACTIVE_FILE.bak"
if [ -f "$ACTIVE_FILE" ]; then
  cp "$ACTIVE_FILE" "$ACTIVE_FILE.bak"
fi
restore_active() {
  if [ -f "$ACTIVE_FILE.bak" ]; then
    mv "$ACTIVE_FILE.bak" "$ACTIVE_FILE"
  else
    rm -f "$ACTIVE_FILE"
  fi
}
write_active "$NEXT"
if ! docker compose exec -T nginx nginx -t -q; then
  restore_active
  fail_before_switch "nginx 설정 검사(nginx -t)를 통과하지 못했다. active-app.inc는 되돌렸지만, git pull로 바뀐 default.conf나 ssl 파일이 깨졌다면 그대로 남아 있다 (nginx가 다시 켜질 때 뜨지 못하니 먼저 고친다)"
fi
# reload: nginx가 설정을 다시 읽는다. 새 요청은 새 자리로 가고, 옛 자리로 가던 요청은 옛 일꾼이 끝까지 마친다 (끊김 없음)
# 같은 폴더의 default.conf가 git pull로 바뀌었다면 그것도 이때 같이 적용된다
if ! docker compose exec -T nginx nginx -s reload; then
  restore_active
  fail_before_switch "nginx reload에 실패했다. active-app.inc는 되돌렸다"
fi
log "바꿔치기: 사이트가 이제 $NEXT를 본다"
# nginx 접속 기록에 배포 때를 남긴다 (502가 없어진 뒤로는 기록만 보고 배포 때를 알 수 없어서. 결과는 보지 않는다)
docker compose exec -T nginx wget -q -O /dev/null -T 5 -U "$UA switched-to-$NEXT $TAG" http://127.0.0.1/ >/dev/null 2>&1 || true

# ── 6. 옛 자리를 끈다 ──
if [ -n "$CURRENT" ] || [ "$LEGACY" = 1 ]; then
  # 옛 nginx 일꾼("worker process is shutting down")은 바꿔치기 전에 받은 요청을 끝까지 옛 자리로 보낸다.
  # 느린 휴대폰의 사진 올리기는 nginx가 다 받은 뒤에야 옛 자리로 넘기므로, 일꾼이 모두 끝날 때까지 기다린다 (고정 15초로는 모자랐다)
  log "옛 nginx 일꾼이 하던 요청을 마칠 때까지 기다림 (최대 ${DRAIN_MAX_SECONDS}초)"
  sleep 2
  DRAIN_END=$(( $(date +%s) + DRAIN_MAX_SECONDS ))
  DRAINED=0
  while [ "$(date +%s)" -lt "$DRAIN_END" ]; do
    PS_OUT=$(docker compose exec -T nginx ps 2>/dev/null || true)
    if ! grep -q 'worker process is shutting down' <<< "$PS_OUT"; then
      DRAINED=1
      break
    fi
    sleep 1
  done
  if [ "$DRAINED" != 1 ]; then
    warn "${DRAIN_MAX_SECONDS}초가 지나도 옛 nginx 일꾼이 남아 있다. 옛 자리를 그대로 끈다"
  fi
fi
# 끄기 전에 새 자리를 한 번 더 확인한다. 그새 죽었으면(메모리 부족 등) 아직 켜져 있는 옛 자리로 되돌린다
if ! nginx_wget -O /dev/null -T 5 "http://$NEXT:8080/actuator/health"; then
  if [ -n "$CURRENT" ] || [ "$LEGACY" = 1 ]; then
    if [ -n "$CURRENT" ]; then write_active "$CURRENT"; else write_active app; fi
    docker compose exec -T nginx nginx -s reload || true
    docker compose stop "$NEXT" || true
    log "실패: 바꿔치기 뒤 $NEXT가 응답하지 않아 $CURRENT_LABEL로 되돌렸다"
    exit 1
  fi
  warn "$NEXT가 응답하지 않는데 되돌아갈 자리가 없다"
fi
# 이제 .env에 그 자리의 태그를 적는다. 다른 자리의 태그는 건드리지 않는다.
# 적어 두어야 나중에 docker compose up -d app1 처럼 직접 켜도 같은 이미지가 유지된다
set_env "$TAG_VAR" "$TAG" || warn ".env에 $TAG_VAR를 적지 못했다"
rm -f "$ACTIVE_FILE.bak"

# 옛 자리를 끈다. compose.yaml의 stop_grace_period(60초) 안에서 Spring이 받던 요청과 뒤에서 하던 AI 분석을 마저 끝낸다.
# 끈 컨테이너는 지우지 않는다: 그 이미지가 서버에 남아 바로 앞 버전으로 빨리 되돌릴 수 있고, 다음 배포 때 그 자리를 새 이미지로 다시 만든다.
# 여기서부터의 실패는 사이트에 영향이 없어서 경고만 하고 배포는 성공으로 끝낸다
if [ -n "$CURRENT" ]; then
  docker compose stop "$CURRENT" || warn "옛 자리 $CURRENT를 끄지 못했다 (자바 둘이 떠 있다): docker compose stop $CURRENT"
fi
# 이 방식으로 처음 배포할 때: 옛 구성의 app 컨테이너를 끄고 지운다 (compose.yaml에 app 서비스가 없어서 compose로는 다루지 않는다)
if legacy_exists; then
  if docker stop -t 60 "$LEGACY_CONTAINER" >/dev/null && docker rm "$LEGACY_CONTAINER" >/dev/null; then
    log "옛 구성의 $LEGACY_CONTAINER를 지움 (처음 한 번)"
  else
    warn "옛 구성의 $LEGACY_CONTAINER를 지우지 못했다: docker stop $LEGACY_CONTAINER && docker rm $LEGACY_CONTAINER"
  fi
fi

# ── 7. nginx, certbot의 compose 설정이 바뀌었으면 적용한다 ──
# 예전 deploy.sh는 docker compose up -d로 모든 서비스를 맞췄다. 이제 app은 위에서 자리마다 다루므로, 나머지만 이름을 붙여 맞춘다.
# 설정이 그대로면 아무것도 하지 않는다. nginx의 이미지나 compose 설정이 바뀐 배포에서만 nginx가 다시 만들어져 1~2초 끊긴다
# (default.conf 같은 설정 파일만 바뀐 것은 5단계의 reload로 이미 적용됐다)
# shellcheck disable=SC2086
if ! docker compose up -d --no-deps ${DEPLOY_SERVICES:-nginx certbot}; then
  log "실패: nginx, certbot 설정을 적용하지 못했다. docker compose ps 와 docker compose logs nginx 를 본다"
  exit 1
fi

# 어떤 컨테이너도 쓰지 않는 이미지를 지워 디스크가 차는 것을 막는다.
# 꺼 둔 옛 자리의 컨테이너는 남아 있으므로 그 이미지는 지워지지 않는다
if [ "${DEPLOY_PRUNE:-1}" = 1 ]; then
  docker image prune -af >/dev/null || warn "쓰지 않는 이미지를 지우지 못했다 (다음 배포 때 다시 한다)"
fi

log "완료: $NEXT = $TAG"
docker compose ps || true
