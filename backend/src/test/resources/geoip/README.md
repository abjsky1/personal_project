# 시험용 IP 위치 파일 (JM-86)

`test-city.mmdb`는 `IpRegionResolverTest`가 쓰는 아주 작은 파일(542바이트)이다. DB-IP 무료판(IP to City Lite)과 같은 형식(MaxMind DB 2판)과 같은 모양(나라 코드, 영어 시/도 이름)으로 이 프로젝트가 직접 만들었다. DB-IP의 자료는 들어 있지 않다.

| IP 대역 | 나라 | 시/도 (영어) | 기대하는 결과 |
|---|---|---|---|
| 1.0.0.0/8 | KR | Seoul | 서울특별시 |
| 2.0.0.0/8 | KR | Gyeonggi-do | 경기도 |
| 3.0.0.0/8 | KR | (없음) | 알 수 없음 |
| 4.0.0.0/8 | US | California | 해외 |
| 그 밖 | | | 알 수 없음 |

다시 만들 때는 이 폴더에서:

```
python make_test_mmdb.py test-city.mmdb
```

서버에서 쓰는 실제 파일(약 121MB)은 git에 넣지 않고 서버의 `deploy/geoip/`에만 둔다 (기획서 16절).
