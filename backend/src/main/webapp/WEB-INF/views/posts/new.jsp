<%-- 글쓰기 화면 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Spring의 form 태그: 입력칸을 Controller가 넘긴 form 객체와 연결하고, 검사 메시지를 칸마다 보여 준다. --%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>사진 올리기</title>
<link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main>
	<header class="top">
		<a href="/">← 목록</a>
		<h1>사진 올리기</h1>
	</header>

	<%-- modelAttribute="form": Controller가 Model에 넣은 "form" 객체와 이 form을 연결한다. --%>
	<%-- enctype="multipart/form-data": 파일을 보낼 때 꼭 필요한 전송 방식 (없으면 파일 이름만 가고 내용은 안 간다) --%>
	<%-- cssClass="narrow": 입력칸이 너무 길어지지 않게 글쓰기 영역만 가운데 560px로 좁힌다. --%>
	<form:form modelAttribute="form" action="/posts" method="post" enctype="multipart/form-data" cssClass="narrow">

		<%-- 휴대폰에서 누르면 "사진 찍기 / 앨범에서 고르기"를 고를 수 있다. --%>
		<%-- accept: 고를 수 있는 파일 형식. 아이폰은 이 설정을 보고 HEIC 사진을 JPEG로 바꿔서 올린다. --%>
		<label for="image">사진</label>
		<input type="file" id="image" name="image" accept="image/jpeg,image/png">
		<p class="hint">JPEG·PNG, 10MB까지. 올린 사진의 위치 정보(EXIF)는 지우고 저장해요.</p>
		<%-- form:errors: 이 칸에 검사 메시지가 있을 때만 <p class="error">메시지</p>를 만든다. --%>
		<form:errors path="image" cssClass="error" element="p" />

		<%-- form:input path="nickname": name="nickname"인 입력칸. 검사에 걸려 화면이 다시 나올 때 입력했던 값이 그대로 채워진다. --%>
		<label for="nickname">닉네임</label>
		<form:input path="nickname" maxlength="20" />
		<form:errors path="nickname" cssClass="error" element="p" />

		<%-- form:password: 보안을 위해 화면이 다시 나올 때 값을 채우지 않는다. --%>
		<label for="password">비밀번호</label>
		<form:password path="password" maxlength="20" />
		<p class="hint">글을 지울 때 필요해요. 잊으면 직접 지울 수 없어요.</p>
		<form:errors path="password" cssClass="error" element="p" />

		<label for="memo">메모 (선택)</label>
		<form:textarea path="memo" maxlength="300" rows="3" />
		<form:errors path="memo" cssClass="error" element="p" />

		<%-- 위치·촬영 시각 제공 동의 (선택, 처음에는 체크되지 않은 상태) --%>
		<%-- form:checkbox: 체크하면 locationConsent=true가 전송된다. 체크를 안 했을 때도 false로 받을 수 있게 Spring이 숨은 칸을 함께 만든다. --%>
		<%-- 무엇을, 왜, 얼마나 보관하는지와 거부해도 불이익이 없다는 것을 체크박스 옆에 알린다. (기획서 8절) --%>
		<label class="checkbox">
			<form:checkbox path="locationConsent" />
			위치·촬영 시각 제공에 동의해요 (선택)
		</label>
		<p class="hint">
			사진에 담긴 위치(GPS)와 찍은 시각을 지역·시간대별 음식 통계에 써요.
			위치는 다른 사람에게 공개되지 않고, 글을 지우면 함께 지워져요. 동의하지 않아도 글을 올릴 수 있어요.
			<%-- target="_blank": 새 탭에서 연다 → 쓰던 글이 사라지지 않는다 --%>
			<a href="/privacy" target="_blank">개인정보처리방침</a>
		</p>

		<button type="submit">올리기</button>
	</form:form>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>

<script>
	const form = document.querySelector('form');
	const button = form.querySelector('button[type="submit"]');

	form.addEventListener('submit', function (event) {
		// 10MB가 넘는 사진은 보내기 전에 막는다.
		// 서버도 10MB에서 거절하지만, 큰 파일을 끝까지 다 보낸 뒤에 거절당하면 그동안 기다려야 하므로 먼저 확인한다.
		const file = document.getElementById('image').files[0];
		if (file && file.size > 10 * 1024 * 1024) {
			// preventDefault(): form 제출을 멈춘다.
			event.preventDefault();
			alert('사진은 10MB까지 올릴 수 있어요.');
			return;
		}

		// 여러 번 눌러서 같은 글이 여러 개 올라가는 것을 막는다. (휴대폰 테스트에서 5초 동안 9개가 올라간 문제)
		// 사진을 보내고 처리하는 데 몇 초가 걸리는데, 그동안 화면이 그대로면 사용자가 버튼을 또 누르게 된다.
		// → 첫 번째 누름에서 버튼을 바로 막고(disabled), 기다리는 중이라는 것을 글자로 보여 준다.
		button.disabled = true;
		button.textContent = '올리는 중…';
	});

	// 아이폰 Safari 등은 "뒤로 가기"를 하면 이전 화면을 그대로 되살린다(bfcache). 그러면 버튼이 막힌 채로 남아 있게 된다.
	// pageshow: 화면이 보일 때마다(되살아날 때 포함) 실행된다 → 버튼을 원래대로 돌려놓는다.
	window.addEventListener('pageshow', function () {
		button.disabled = false;
		button.textContent = '올리기';
	});
</script>
</body>
</html>
