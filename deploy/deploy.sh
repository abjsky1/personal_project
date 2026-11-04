#!/bin/bash
# 서버에서 app을 새 이미지로 바꾸는 스크립트. 평소에는 GitHub Actions가 SSM으로 실행한다 (ci.yml의 deploy job).
# 사람이 직접 실행해도 된다 (deploy 폴더에서):
#   bash deploy.sh <커밋 SHA>          ← 그 커밋으로 만든 이미지로 바꾼다
#   bash deploy.sh <예전 커밋 SHA>     ← 되돌리기(롤백). 이미지는 GHCR에 남아 있으므로 다시 받기만 하면 된다
# 이미지가 아예 없는 비상시(GHCR 장애 등)에는 예전 방식 그대로:  docker compose up -d --build

# -e: 명령이 하나라도 실패하면 바로 멈춘다 / -u: 없는 변수를 쓰면 오류 / -o pipefail: 파이프 중간의 실패도 실패로
set -euo pipefail
# 이 파일이 있는 폴더(deploy)로 이동 → 어디서 실행해도 compose.yaml·.env를 찾는다
cd "$(dirname "$0")"

TAG="${1:?사용법: bash deploy.sh <이미지 태그(커밋 SHA)>}"

# .env의 APP_IMAGE_TAG를 바꾼다 (없으면 줄을 추가). compose.yaml이 이 값으로 받을 이미지를 고른다.
# 파일에 적어 두므로, 나중에 docker compose up -d 를 다시 실행해도 같은 이미지가 유지된다.
if grep -q '^APP_IMAGE_TAG=' .env; then
  sed -i "s/^APP_IMAGE_TAG=.*/APP_IMAGE_TAG=$TAG/" .env
else
  echo "APP_IMAGE_TAG=$TAG" >> .env
fi

echo "[deploy] 이미지 받기: $TAG"
docker compose pull --quiet app

echo "[deploy] app 컨테이너 교체 (약 20초 멈춤)"
# --remove-orphans: compose.yaml에서 사라진 서비스의 컨테이너가 남아 있으면 지운다
docker compose up -d --remove-orphans

echo "[deploy] app이 뜰 때까지 기다림 (최대 2분)"
for i in $(seq 1 24); do
  # compose.yaml의 healthcheck 결과: starting → healthy
  HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || echo unknown)
  if [ "$HEALTH" = "healthy" ]; then
    break
  fi
  if [ "$i" = "24" ]; then
    echo "[deploy] 실패: 2분이 지나도 healthy가 아님 (상태: $HEALTH). 최근 로그:"
    docker compose logs app --tail 50
    exit 1
  fi
  sleep 5
done

# nginx가 설정 파일(nginx.conf)을 다시 읽게 한다 (끊김 없음).
# 파일 내용만 바뀐 경우 compose는 nginx 컨테이너를 새로 만들지 않기 때문에, 여기서 직접 reload해야 바뀐 설정이 적용된다.
docker compose exec -T nginx nginx -s reload

# 어떤 컨테이너도 쓰지 않는 이미지(교체된 예전 app 이미지 등)를 지워 디스크가 차는 것을 막는다.
# 되돌릴 때는 GHCR에서 다시 받으므로 서버에 남겨 둘 필요가 없다.
docker image prune -af >/dev/null

echo "[deploy] 완료: $TAG"
docker compose ps
