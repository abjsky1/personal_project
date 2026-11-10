# 자동 배포가 AWS 권한 단계에서 실패함 (GitHub OIDC 증명서의 sub 형식 변경)

- **날짜**: 2026-11-04 (S4-5, 첫 자동 배포)
- **영향**: 없음. 서버에 닿기 전 단계라 서비스는 그대로였다. 배포만 두 번 실패했다.

## 현상
main에 push하자 테스트, 이미지 빌드는 통과했는데, deploy의 첫 단계(`aws-actions/configure-aws-credentials`, AWS에서 임시 권한 받기)가 12번 재시도 끝에 실패했다.

```
Error: Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity
```

IAM 역할 `macros-deploy`의 신뢰 정책은 콘솔의 "웹 자격 증명" 마법사가 만든 그대로였고, 보기에는 전부 맞았다 (공급자 ARN, `aud` = `sts.amazonaws.com`, `sub` = `repo:abjsky1/personal_project:ref:refs/heads/main`). 반영 지연인가 싶어 재실행해도 같았다.

## 원인
이 오류는 AWS가 "증명서는 받았지만 신뢰 정책 조건에 안 맞는다"일 때 내는 한 가지 메시지라, 어느 조건이 틀렸는지는 말해 주지 않는다. 그래서 워크플로에 임시 단계를 넣어 GitHub이 실제로 보내는 증명서의 내용(발급자, 대상, 주체)만 찍어 봤다 (커밋 `2891c0c`, 원인을 찾은 뒤 지움).

```
"sub": "repo:abjsky1@231160078/personal_project@1437024222:ref:refs/heads/main"
```

GitHub이 2026-04-23에 공지한 변경이다. **2026-07-15 이후 만든 저장소**는 `sub`의 사용자, 저장소 이름 뒤에 바뀌지 않는 고유 번호를 붙인다 (이름을 바꾸거나 지웠다 다시 만든 저장소가 예전 권한을 물려받는 것을 막기 위해). 우리 저장소는 2026-10에 만들었으니 새 형식인데, AWS 콘솔 마법사는 아직 옛 형식 `repo:이름/저장소:ref:…`로 정책을 만들어서 글자가 안 맞았다.

## 해결
신뢰 정책의 `sub` 조건에 새 형식을 넣었다 (옛 형식도 함께 둠). 고유 번호는 증명서에서 읽었다: 사용자 231160078, 저장소 1437024222. (사용자 번호는 GitHub noreply 메일 주소 앞의 숫자와 같다)

```json
"token.actions.githubusercontent.com:sub": [
  "repo:abjsky1@231160078/personal_project@1437024222:ref:refs/heads/main",
  "repo:abjsky1/personal_project:ref:refs/heads/main"
]
```

저장 뒤 실패한 job만 재실행 → 통과. 서버에서 이미지를 받아 app이 교체되고, 밖에서 사이트 확인까지 되는 것을 확인했다.

## 재발 방지
- 신뢰 정책에 `sub`를 적을 때는 문서의 예시나 마법사 결과가 아니라 **증명서에 실제로 찍힌 값**을 쓴다. 확인 방법은 커밋 `2891c0c`의 "증명서 내용 확인" 단계 (증명서 자체는 찍지 않고 내용만).
- 저장소 Settings → Actions → OIDC의 "immutable subject claim" 설정은 끄지 않는다. 새 형식이 더 안전하다.

## 배운 것
- "Not authorized"처럼 뭉뚱그린 오류는 설정을 더 들여다보는 것보다 **실제 값을 찍어서 비교**하는 게 빠르다. 재실행으로 한 번 시간을 쓴 뒤에야 진단 단계를 넣었다.
- 콘솔 마법사가 만든 정책도 틀릴 수 있다. 외부 서비스(GitHub)의 형식 변경을 AWS가 바로 따라가지 않는다.

## 참고
- [GitHub 변경 공지: Immutable subject claims for GitHub Actions OIDC tokens (2026-04-23)](https://github.blog/changelog/2026-04-23-immutable-subject-claims-for-github-actions-oidc-tokens)
- [GitHub 문서: OIDC reference](https://docs.github.com/en/actions/reference/security/oidc)
- [AWS re:Post: 이 오류의 흔한 원인들](https://www.repost.aws/questions/QUPqSGcz54SI-CTgAJ2jlHmg/issue-with-assuming-role-in-aws-using-github-actions-not-authorized-to-perform-sts-error)
