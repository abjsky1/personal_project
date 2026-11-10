# enum에 상태를 추가했더니 저장이 실패함 (Hibernate의 CHECK 제약)

- **날짜**: 2026-11-02 (S2-4, 배포 전 개발 중 발견)
- **영향**: 없음. 테스트에서 발견했다. 그대로 배포했다면 다음에 상태를 추가할 때 운영에서 터졌을 문제다.

## 현상
분석 상태에 `LIMIT_EXCEEDED`를 추가하고 저장 테스트를 돌리자 실패했다.

```
DataIntegrityViolationException: Check constraint 'food_analyses_chk_1' is violated.
```

## 원인
Hibernate가 테이블을 만들 때 enum 컬럼에 허용 값 목록을 CHECK 제약으로 붙인다. 코드에는 어디에도 적혀 있지 않다.

```sql
check (status in ('PENDING', 'DONE', 'NOT_FOOD', 'FAILED'))
```

`ddl-auto=update`는 없는 테이블, 컬럼만 추가하고, 이미 있는 제약은 고치지 않는다. 그래서 enum에 값을 추가해도 DB는 예전 목록으로 새 값을 거절한다.

S1에서 비슷한 문제(MySQL ENUM 타입)를 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 막았지만, 컬럼 타입만 VARCHAR로 바뀔 뿐 CHECK 제약은 그대로 만들어지고 있었다. `posts.status`에도 같은 제약이 있었다.

**왜 위험한가**: 분석은 뒤에서 돌기 때문에 저장이 실패해도 사용자에게 에러가 보이지 않는다. 글이 계속 "분석 중"으로 남을 뿐이라 원인을 찾기 어렵다. 빈 DB에서는 재현되지 않아서 CI도 통과한다 (테이블을 새로 만들 때는 새 값까지 목록에 들어간다). 이미 테이블이 있는 개발 DB에서만 드러났다.

## 해결
1. `MySQLDialect`를 상속한 `NoColumnCheckMySQLDialect`에서 `supportsColumnCheck()`가 `false`를 돌려주게 하고, `spring.jpa.database-platform`으로 지정했다. 이걸 끄는 설정은 찾지 못했다.
2. 이미 있는 제약은 저절로 사라지지 않으므로 개발 DB에서 직접 지웠다.

```sql
ALTER TABLE posts DROP CHECK posts_chk_1;
ALTER TABLE food_analyses DROP CHECK food_analyses_chk_1;
```

운영 DB는 아직 없어서 처음부터 제약 없이 만들어진다. 허용 값은 Java enum이 지키므로 DB 제약이 없어도 된다.

## 재발 방지
- `NoColumnCheckMySQLDialectTest`: DB에 CHECK 제약이 없는지 확인한다. Hibernate를 올리다 설정이 풀리면 CI에서 바로 알 수 있다.
- `ddl-auto=update`는 **추가만** 한다. 컬럼 변경, 삭제나 제약 변경이 필요해지면 그 SQL은 직접 실행해야 한다 (기획서 18절).

## 배운 것
- 뒤에서 도는 작업의 실패는 화면에 드러나지 않는다. 저장 자체가 실패하면 실패 기록도 남지 않으므로 로그를 봐야 한다.
- 새 DB에서만 도는 테스트(CI)와 오래 쓴 DB(개발, 운영)는 결과가 다를 수 있다.

## 참고
- [Spring Data JPA 이슈: 새 enum 값을 넣어도 CHECK 제약이 바뀌지 않음](https://github.com/spring-projects/spring-data-jpa/issues/3699)
- [Hibernate 포럼: enum 제약을 끄는 방법](https://discourse.hibernate.org/t/v6-6-33-how-disable-enum-constraints/12025)
