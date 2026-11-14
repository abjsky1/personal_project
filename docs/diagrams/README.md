# 구조 그림

찍먹의 구조를 한눈에 보는 그림 네 장이다 (2026-11-14 기준. 처음 그린 것은 JM-78, 관리자 화면은 JM-82에서 더했다).

| 그림 | 보여 주는 것 | 기획서 |
|---|---|---|
| [deploy-architecture.png](deploy-architecture.png) | 코드가 서버까지 가는 길(배포)과 사용자의 요청이 지나가는 길 | 10절 |
| [resource-map.png](resource-map.png) | EC2, RDS, S3 같은 자원마다 무엇이 들어 있는지, 비밀값은 어디에 있는지 | 10절 |
| [site-ia.png](site-ia.png) | 사이트 구조(IA): 화면 10개와 에러 화면, 메뉴, 메뉴에 없는 주소(관리자 화면 포함) | 14절 |
| [ai-analysis-flow.png](ai-analysis-flow.png) | 사진 한 장이 칼로리가 되기까지 (AI 분석 흐름) | 6절 |

## 고쳐 그리기

같은 이름의 `.html`이 원본이다. 구성이 바뀌면 HTML의 글자를 고치고, 이 폴더에서 PowerShell로 다시 찍는다.

```
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --hide-scrollbars --force-device-scale-factor=2 --window-size=1600,1313 --screenshot="$PWD\resource-map.png" "$PWD\resource-map.html"
```

- 파일 이름 두 곳과 높이(`--window-size`의 두 번째 숫자)만 그림마다 바꾼다. 높이: deploy-architecture 1100, site-ia 1173, ai-analysis-flow 1296, resource-map 1313. 내용이 늘어 아래가 잘리면 높이를 늘린다.
- 글꼴은 Noto Sans KR이다. PC에 없으면 다른 글꼴로 그려져 글자가 상자를 넘칠 수 있다.
- 비밀값은 이름만 적는다. 값(비밀번호, 키, 토큰)과 버킷 이름, RDS 주소는 그림에 넣지 않는다.
