# 행정동 경계 (JM-85)

`hangjeongdong.geojson.gz`는 운영자 통계의 "사진 위치"에서 사진을 찍은 곳이 어느 행정동인지 찾는 데 쓴다 (`RegionLookup`, 기획서 6.2절). 서버가 처음 찾을 때 한 번 읽는다.

## 출처와 라이선스

- 원본: [vuski/admdongkor](https://github.com/vuski/admdongkor)의 `ver20261001/HangJeongDong_ver20261001.geojson` (2026-10-01판, 행정동 3,559개)
- 라이선스: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). 원자료는 통계청 SGIS가 공공누리 제1유형(출처표시)으로 개방한 행정동 경계다
- 원본 저장소가 요구하는 출처 표기 (LICENSE-DATA 3절 그대로):

  > 본 데이터는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)에서 공공누리 제1유형으로 개방한 행정동 경계를 가공한 것이며(가공: vuski/admdongkor, https://github.com/vuski/admdongkor), CC BY 4.0으로 배포됩니다.

- 이 프로젝트가 바꾼 것: 경계선을 30m 간격으로 줄이고(꼭짓점을 덜어 냄), 좌표를 소수점 다섯 자리로 자르고, 이름 칸 네 개(`adm_nm`, `adm_cd2`, `sidonm`, `sggnm`)만 남겨 gzip으로 묶었다. 이름은 원본 그대로 두고, 읽을 때 가운뎃점(U+00B7, 종로1과 2와 3과 4가를 묶은 동 같은 이름에 있다)은 쉼표로 바꾸고, 일반구가 있는 시(원본에 `안양시만안구`처럼 붙어 있는 39곳)는 `안양시 만안구`처럼 띄워 낸다 (`RegionLookup`)

## 만든 방법 (2026-11-17)

원본 파일(34,652,038바이트)을 내려받은 폴더에서:

```
npx --yes mapshaper@0.7.101 HangJeongDong_ver20261001.geojson -filter-fields adm_nm,adm_cd2,sidonm,sggnm -simplify interval=30 keep-shapes -o precision=0.00001 hangjeongdong.geojson
gzip -9 -n hangjeongdong.geojson
```

줄인 파일은 7,727,608바이트, gzip으로 묶으면 약 1.8MB다.

## 확인한 것

- 원본과 줄인 파일에서 같은 방법(짝홀 규칙)으로 점 3,000개의 행정동을 찾아 비교했다 (한국 땅 위에 고르게 1,500개, 행정동을 고르게 골라 그 안에 1,500개). 30m는 다른 결과가 0개였다. 50m는 0.33%, 100m는 1.33%가 달라서 30m로 정했다
- 행정동 수는 원본과 같은 3,559개다 (`keep-shapes`로 작은 섬도 남김)

## 새 판으로 바꿀 때

같은 명령으로 만들고, `RegionLookupTest`의 기대 이름을 새 원본에서 다시 확인한다. 이 문서와 `RegionLookup`의 판 날짜, 기획서 18절의 결정 사항도 고친다.
