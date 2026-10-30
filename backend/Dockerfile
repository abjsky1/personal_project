# 배포용 Docker 이미지: 이 서버(war)를 어느 컴퓨터에서든 똑같이 실행할 수 있게 "실행 환경째로" 포장한다.
# 만들기: backend 폴더에서 docker build -t macros-app .   (보통은 deploy/compose.yaml이 대신 실행한다)
#
# 두 단계로 나눈다 (multi-stage build)
#   1단계(build): JDK와 Gradle로 war 파일을 만든다.
#   2단계(실행):  만든 war 파일만 새 이미지에 복사해서 실행한다.
#   → 소스 코드·Gradle·빌드 중에 받은 라이브러리 캐시는 최종 이미지에 남지 않아서 이미지가 가볍고, 서버에 소스가 올라가지 않는다.

# ---- 1단계: war 만들기 ----
# amazoncorretto:17-alpine: build.gradle의 toolchain과 같은 Amazon Corretto 17. alpine = 아주 작은 리눅스
# AS build: 이 단계에 이름을 붙인다 (2단계에서 "build 단계의 파일을 가져와"라고 부를 때 쓴다)
FROM amazoncorretto:17-alpine AS build
# 컨테이너 안의 작업 폴더 (없으면 만든다)
WORKDIR /src
# backend 폴더를 통째로 복사한다 (.dockerignore에 적은 build·테스트 코드 등은 빼고)
COPY . .
# bootWar: 실행 가능한 war 하나만 만든다 (테스트는 CI가 push마다 이미 돌린다)
# --mount=type=cache: Gradle이 내려받은 라이브러리를 빌드가 끝나도 지우지 않고 보관한다
#   → 다음 빌드부터는 다시 내려받지 않아서 빨라진다. (이 보관함은 이미지에는 들어가지 않는다)
# --no-daemon: Gradle을 백그라운드에 띄워 두지 않는다 (한 번 빌드하고 끝나는 컨테이너라 필요 없다)
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootWar --no-daemon

# ---- 2단계: 실행 ----
FROM amazoncorretto:17-alpine
WORKDIR /app
# 관리자(root)가 아닌 일반 사용자 app을 만들어 그 권한으로 실행한다.
# 서버 프로그램에 빈틈이 있어 누가 들어오더라도 컨테이너 안에서 할 수 있는 일을 줄인다.
# uploads: 사진을 S3 대신 폴더에 저장할 때(내 PC에서 시험할 때) 쓰는 폴더. app 사용자가 쓸 수 있게 주인을 바꿔 둔다.
RUN addgroup -S app && adduser -S app -G app && mkdir uploads && chown app:app uploads
# 1단계(build)에서 만든 war만 가져온다
COPY --from=build /src/build/libs/*.war app.war
USER app
# 이 컨테이너가 8080번으로 요청을 받는다는 표시 (실제로 밖에 여는 것은 compose.yaml이 정한다)
EXPOSE 8080
# 컨테이너가 켜지면 실행할 명령. 메모리 같은 JVM 옵션은 compose.yaml의 JAVA_TOOL_OPTIONS로 넣는다.
ENTRYPOINT ["java", "-jar", "app.war"]
