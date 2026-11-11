<%-- 공지 (JM-65): 언제 무엇이 바뀌었는지 사용자에게 알린다. 최신 공지가 위에 온다. --%>
<%-- 새 공지는 맨 위 <article> 하나를 복사해서 날짜, 제목, 설명만 바꾼다. 사용자에게 보이는 변경을 내보내는 PR에서 같이 더한다 (기획서 16절) --%>
<%-- 개발 용어 대신 쓰는 사람 말로 쓴다. 피드백에서 온 변경은 "(피드백 N번 반영)"을 붙인다 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>공지 - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<%-- 머리: 피드, 피드백 목록과 같은 틀 (app.css의 .top-menu). 버튼 없이 제목과 메뉴 링크만 --%>
	<header class="top top-menu">
		<h1>공지</h1>
		<nav class="site-nav">
			<a href="/">피드</a>
			<a href="/notice" class="active" aria-current="page">공지</a>
			<a href="/feedback">개발자에게 피드백 보내기</a>
		</nav>
	</header>

	<p class="hint">찍먹에서 바뀐 것을 날짜순으로 알려 드려요. 바라는 점은 피드백 게시판에 남겨 주세요.</p>

	<section class="notice-list">
		<article class="notice">
			<p class="notice-date">2026년 11월 11일</p>
			<h2>공지 페이지를 열었어요</h2>
			<p>앞으로 찍먹이 바뀔 때마다 여기에 날짜와 함께 알려 드릴게요.</p>
		</article>
		<article class="notice">
			<p class="notice-date">2026년 11월 10일</p>
			<h2>메뉴를 알아보기 쉽게 바꿨어요</h2>
			<p>맨 위 메뉴를 "피드", "개발자에게 피드백 보내기" 링크로 바꿨어요. 지금 보고 있는 화면은 초록색으로 보여요.</p>
			<p>화면 글자도 "글이나 댓글"처럼 읽기 쉽게 다듬었어요.</p>
		</article>
		<article class="notice">
			<p class="notice-date">2026년 11월 7일</p>
			<h2>목록을 쪽 번호로 넘겨요</h2>
			<p>"더 보기" 대신 맨 아래 쪽 번호로 넘겨요. 다른 쪽에 갔다가 앞 쪽으로 다시 돌아올 수 있어요. (피드백 8번 반영)</p>
		</article>
		<article class="notice">
			<p class="notice-date">2026년 11월 7일</p>
			<h2>휴대폰에서 사진을 한 줄에 세 장씩 보여 줘요</h2>
			<p>휴대폰 피드가 한 줄에 한 장이라 길었는데, 세 장씩 보이게 바꿨어요. (피드백 3번 반영)</p>
		</article>
		<article class="notice">
			<p class="notice-date">2026년 11월 5일</p>
			<h2>찍먹 지인 베타를 시작했어요</h2>
			<p>먹은 사진을 올리면 AI가 칼로리와 탄수화물, 단백질, 지방을 알려줘요.</p>
			<p>댓글과 피드백 게시판, 이용약관과 개인정보처리방침도 함께 열었어요.</p>
		</article>
	</section>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
