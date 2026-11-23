#!/bin/bash
# 서버에서 app을 새 이미지로 바꾸는 스크립트 (무중단 배포, JM-62). 평소에는 GitHub Actions가 SSM으로 실행한다 (ci.yml의 deploy job).
# 사람이 직접 실행해도 된다 (deploy 폴더에서):
#   bash deploy.sh <커밋 SHA>          ← 그 커밋으로 만든 이미지로 바꾼다
#   bash deploy.sh <예전 커밋 SHA>     ← 되돌리기(롤백). 이미지는 GHCR에 남아 있으므로 다시 받기만 하면 된다. 되돌릴 때도 끊기지 않는다
#
# app은 app1, app2 두 자리가 있고 평소에는 한쪽만 켜져 있다 (compose.yaml). 바꾸는 순서:
#   1. 지금 사이트를 맡은 자리를 알아낸다 (nginx/active-app.inc)
#   2. 다른 자리를 새 이미지로 켠다 (그동안 사용자는 계속 옛 자리를 쓴다)
#   3. 새 자리가 요청을 받을 수 있을 때까지 기다린다. 끝내 안 되면 새 자리만 끄고 멈춘다 (사이트는 옛 자리 그대로)
#   4. 데운다: 화면마다 한 번씩 미리 불러, 배포 뒤 첫 사용자가 JSP 컴파일을 기다리지 않게 한다
#   5. nginx가 보는 자리를 바꾸고 reload한다 (reload는 하던 연결을 끊지 않는다)
#   6. 옛 자리가 받던 요청을 마저 끝내도록 잠깐 기다린 뒤 끈다
#
# 내 PC 리허설용 값 (서버에서는 쓰지 않는다):
#   NGINX_DIR=nginx-local   nginx 설정 폴더 (compose.local.yaml이 nginx-local을 붙인다)
#   DEPLOY_PRUNE=0          쓰지 않는 이미지를 지우지 않는다 (내 PC의 다른 프로젝트 이미지까지 지우지 않게)
#
# 이미지가 아예 없는 비상시(GHCR 장애 등)에는 끊김을 감수하고 서버에서 직접 빌드한다 (compose.yaml 맨 위 주석)

# -e: 명령이 하나라도 실패하면 바로 멈춘다 / -u: 없는 변수를 쓰면 오류 / -o pipefail: 파이프 중간의 실패도 실패로
set -euo pipefail
# 이 파일이 있는 폴더(deploy)로 이동 → 어디서 실행해도 compose.yaml, .env를 찾는다
cd "$(dirname "$0")"

TAG="${1:?사용법: bash deploy.sh <이미지 태그(커밋 SHA)>}"
NGINX_DIR="${NGINX_DIR:-nginx}"
# nginx가 지금 보는 자리를 적어 두는 파일. nginx 설정 폴더 안에 있어서 nginx 컨테이너도 /etc/nginx/conf.d/active-app.inc로 본다
ACTIVE_FILE="$NGINX_DIR/active-app.inc"
# 옛 구성(app 한 자리)일 때의 컨테이너 이름. 이 방식으로 처음 배포할 때 한 번만 쓰인다
LEGACY_CONTAINER=macros-app-1
# 데우기 요청의 User-Agent. bot이 들어 있어 방문 수, 사용자 흐름, 위치 통계에 세지 않는다 (Visitors.java)
UA="jjikmuk-deploy-bot"
# 옛 자리를 끄기 전에 기다리는 시간(초). 바꾸기 직전에 들어온 요청(사진 올리기 p95 약 7초)이 끝날 시간
DRAIN_SECONDS=15

# 서버의 컨테이너 이름 목록 (옛 구성의 app이 남아 있는지 볼 때 쓴다)
legacy_exists() {
  local names
  names=$(docker ps -a --format '{{.Names}}')
  grep -qx "$LEGACY_CONTAINER" <<< "$names"
}

# ── 1. 지금 사이트를 맡은 자리 ──
CURRENT=""
if [ -f "$ACTIVE_FILE" ]; then
  CURRENT=$(grep -o 'app[12]' "$ACTIVE_FILE" | head -1 || true)
fi
# 이 방식으로 처음 배포할 때: 사이트는 아직 옛 구성의 app이 맡고 있다.
# git pull로 바뀐 default.conf는 active-app.inc가 없으면 app1을 보므로, 그 사실을 먼저 파일에 적어 둔다.
# (안 그러면 아래에서 배포가 실패한 채로 nginx가 6시간마다 하는 reload를 만나, 꺼진 app1을 보게 되어 사이트가 502가 된다)
if [ -z "$CURRENT" ] && legacy_exists; then
  echo "set \$app http://app:8080;" > "$ACTIVE_FILE"
fi
# 맡은 자리가 app1이면 app2를, 아니면(app2, 또는 파일이 없는 처음) app1을 켠다
if [ "$CURRENT" = "app1" ]; then
  NEXT=app2
else
  NEXT=app1
fi
echo "[deploy] 지금 사이트: ${CURRENT:-없음(처음)} → 새 버전을 켤 자리: $NEXT ($TAG)"

# ── 2. 새 자리를 새 이미지로 켠다 ──
# 그 자리의 태그 변수(APP1_TAG 또는 APP2_TAG)를 이 스크립트 안에서만 먼저 바꾼다. compose는 .env보다 이 값을 먼저 쓴다.
# .env에는 바꿔치기가 성공한 뒤에 적는다 (5단계 끝) → 배포가 실패해도 .env는 실제로 배포된 버전 그대로 남는다
TAG_VAR="$(echo "$NEXT" | tr '[:lower:]' '[:upper:]')_TAG"
export "$TAG_VAR=$TAG"

echo "[deploy] 이미지 받기"
# --ignore-pull-failures: GHCR이 잠깐 안 될 때도, 서버에 이미 있는 이미지(바로 앞 버전 등)로는 되돌릴 수 있게 받기 실패를 넘긴다.
# 서버에도 없는 이미지면 아래 up이 다시 받으려다 실패해서 여기서 멈춘다 (사이트는 옛 자리 그대로)
docker compose pull --quiet --ignore-pull-failures "$NEXT"
# --no-deps: 이 자리만 켠다 (서비스 이름을 붙였으므로 다른 자리와 nginx는 그대로)
# --no-build: 이미지를 받지 못해도 서버에서 소스로 빌드하지 않는다. compose.yaml에 비상용 build 줄이 있어서, 이것이 없으면
#   없는 태그를 받으려다 실패한 뒤 서버(메모리 2GB)에서 빌드를 시작해 버린다 (2026-11-23 내 PC 리허설에서 찾음)
docker compose up -d --no-deps --no-build "$NEXT"

# ── 3. 새 자리가 요청을 받을 수 있을 때까지 (최대 2분) ──
# nginx 컨테이너에서 이름으로 불러 본다: nginx가 실제로 그 자리를 찾아갈 수 있는지까지 같이 확인된다.
# (compose.yaml의 healthcheck는 30초마다라서 기다리지 않고, 2초마다 직접 묻는다)
echo "[deploy] $NEXT가 뜰 때까지 기다림"
READY=0
# 횟수가 아니라 시각으로 끊는다 (묻는 데도 1초쯤 걸려서, 횟수로 세면 2분이 3분이 된다)
DEADLINE=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if docker compose exec -T nginx wget -q -O /dev/null -U "$UA" "http://$NEXT:8080/actuator/health" 2>/dev/null; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" != 1 ]; then
  echo "[deploy] 실패: 2분이 지나도 $NEXT가 응답하지 않음. 사이트는 ${CURRENT:-옛 app}가 그대로 맡는다. 최근 로그:"
  docker compose logs "$NEXT" --tail 50 || true
  docker compose stop "$NEXT" || true
  exit 1
fi

# ── 4. 데우기 ──
# 화면(JSP)은 처음 불릴 때 컴파일된다. 사용자를 넘기기 전에 화면마다 한 번씩 불러 둔다.
# 데우기가 실패해도 배포는 멈추지 않는다 (화면 하나가 느릴 뿐, 사이트는 된다)
warm() {
  local start end
  start=$(date +%s%N)
  if docker compose exec -T nginx wget -q -O /dev/null -U "$UA" "http://$NEXT:8080$1" 2>/dev/null; then
    end=$(date +%s%N)
    echo "[deploy] 데우기 $1 $(( (end - start) / 1000000 ))ms"
  else
    echo "[deploy] 데우기 실패(넘어감): $1"
  fi
}
# 목록 화면에서 상세 화면의 번호 하나를 찾는다 (글, 공지, 피드백). 없으면 빈 값
first_id() {
  docker compose exec -T nginx wget -q -O - -U "$UA" "http://$NEXT:8080$1" 2>/dev/null \
    | grep -o "href=\"$2/[0-9]*\"" | head -1 | grep -o '[0-9]*' || true
}
for page in / /posts/new /notice /feedback /feedback/new /terms /privacy; do
  warm "$page"
done
POST_ID=$(first_id / /posts)
NOTICE_ID=$(first_id /notice /notice)
FEEDBACK_ID=$(first_id /feedback /feedback)
[ -n "$POST_ID" ] && warm "/posts/$POST_ID"
[ -n "$NOTICE_ID" ] && warm "/notice/$NOTICE_ID"
[ -n "$FEEDBACK_ID" ] && warm "/feedback/$FEEDBACK_ID"

# ── 5. nginx가 보는 자리를 바꾼다 ──
# 바꾸기 전 파일을 남겨 두고, 새 설정이 틀리면(nginx -t 실패) 되돌린 뒤 새 자리를 끄고 멈춘다
if [ -f "$ACTIVE_FILE" ]; then
  cp "$ACTIVE_FILE" "$ACTIVE_FILE.bak"
fi
echo "set \$app http://$NEXT:8080;" > "$ACTIVE_FILE"
if ! docker compose exec -T nginx nginx -t -q; then
  echo "[deploy] 실패: nginx 설정 검사(nginx -t)를 통과하지 못함. 바꾸기 전으로 되돌린다"
  if [ -f "$ACTIVE_FILE.bak" ]; then
    mv "$ACTIVE_FILE.bak" "$ACTIVE_FILE"
  else
    rm -f "$ACTIVE_FILE"
  fi
  docker compose stop "$NEXT" || true
  exit 1
fi
# reload: nginx가 설정을 다시 읽는다. 새 요청은 새 자리로 가고, 옛 자리로 가던 요청은 끝까지 마친다 (끊김 없음)
# 같은 폴더의 default.conf가 git pull로 바뀌었다면 그것도 이때 같이 적용된다
docker compose exec -T nginx nginx -s reload
echo "[deploy] 바꿔치기: 사이트가 이제 $NEXT를 본다"
# 이제 .env에 그 자리의 태그를 적는다 (없으면 줄을 추가). 다른 자리의 태그는 건드리지 않는다.
# 적어 두어야 나중에 docker compose up -d app1 처럼 직접 켜도 같은 이미지가 유지된다
if grep -q "^$TAG_VAR=" .env; then
  sed -i "s/^$TAG_VAR=.*/$TAG_VAR=$TAG/" .env
else
  echo "$TAG_VAR=$TAG" >> .env
fi

# ── 6. 옛 자리를 끈다 ──
# 옛 자리가 받던 요청을 마저 끝낼 시간을 준 뒤 끈다. 끌 때도 compose.yaml의 stop_grace_period(40초) 안에서
# Spring이 받던 요청과 뒤에서 하던 AI 분석을 마저 끝낸다. 끈 컨테이너는 지우지 않는다(다음 배포 때 그 자리를 새 이미지로 다시 만든다)
if [ -n "$CURRENT" ] || legacy_exists; then
  echo "[deploy] ${DRAIN_SECONDS}초 기다린 뒤 옛 자리를 끈다"
  sleep "$DRAIN_SECONDS"
fi
if [ -n "$CURRENT" ]; then
  docker compose stop "$CURRENT"
fi
# 이 방식으로 처음 배포할 때: 옛 구성의 app 컨테이너를 끄고 지운다 (compose.yaml에 app 서비스가 없어서 compose로는 다루지 않는다)
if legacy_exists; then
  docker stop -t 40 "$LEGACY_CONTAINER" >/dev/null
  docker rm "$LEGACY_CONTAINER" >/dev/null
  echo "[deploy] 옛 구성의 $LEGACY_CONTAINER를 지움 (처음 한 번)"
fi

# 어떤 컨테이너도 쓰지 않는 이미지를 지워 디스크가 차는 것을 막는다.
# 꺼 둔 옛 자리의 컨테이너는 남아 있으므로 그 이미지는 지워지지 않는다 → 바로 앞 버전으로 되돌릴 때 다시 받지 않아도 된다
if [ "${DEPLOY_PRUNE:-1}" = 1 ]; then
  docker image prune -af >/dev/null
fi

echo "[deploy] 완료: $NEXT = $TAG"
docker compose ps
