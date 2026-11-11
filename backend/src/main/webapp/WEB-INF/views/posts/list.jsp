<%-- 게시글 목록 (첫 화면) --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>찍먹</title>
<%-- 모든 화면이 함께 쓰는 스타일 (src/main/webapp/css/app.css) --%>
<%-- c:url: 배포 서버에서는 주소 뒤에 파일 내용의 지문이 붙는다 (/css/app-3f2a….css). 개발 PC에서는 그대로 /css/app.css --%>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<%-- 머리 (JM-60): PC는 첫 줄에 제목 | 한 줄 소개(가운데) | 사진 올리기, 둘째 줄 왼쪽에 메뉴 링크 (사진 바로 위)
	     휴대폰은 첫 줄에 제목과 버튼, 둘째 줄에 한 줄 소개, 셋째 줄에 메뉴 링크 (자리는 app.css의 .top-menu) --%>
	<header class="top top-menu">
		<h1>찍먹</h1>
		<%-- 한 줄 소개: 처음 온 사람이 뭘 하는 곳인지 바로 알게 (11-05). "알려줘요"는 운영자 요청으로 붙여 쓴다 (11-10) --%>
		<p class="slogan">찍고 먹자 !<small>먹은 사진을 올리면 AI가 칼로리를 알려줘요</small></p>
		<a class="button" href="/posts/new">사진 올리기</a>
		<%-- 메뉴 링크: 피드(이 화면), 공지(JM-65), 피드백 게시판. 피드백은 무엇을 하는 곳인지 바로 알게 "개발자에게 피드백 보내기"로 적는다.
		     지금 화면 링크에는 active(초록 굵은 글자, app.css)와 aria-current="page"(화면 읽기 프로그램이 "지금 화면"이라고 읽는다)를 단다 (JM-63) --%>
		<nav class="site-nav">
			<a href="/" class="active" aria-current="page">피드</a>
			<a href="/notice">공지</a>
			<a href="/feedback">개발자에게 피드백 보내기</a>
		</nav>
	</header>

	<%-- empty: 목록이 비어 있으면 true --%>
	<c:if test="${empty feed.posts}">
		<p class="empty">아직 올라온 사진이 없어요.</p>
	</c:if>

	<%-- 카드 격자: PC에서는 한 줄에 여러 장, 휴대폰에서는 한 줄에 세 장 (칸 수는 app.css의 .feed가 화면 폭을 보고 정한다. 세 장은 지인 피드백 3번, JM-50) --%>
	<div class="feed">
		<%-- c:forEach: feed.posts의 글을 하나씩 post라는 이름으로 꺼내 반복한다. --%>
		<c:forEach var="post" items="${feed.posts}">
			<%-- 카드 전체가 링크라서 사진이나 글씨 아무 곳을 눌러도 상세 화면(/posts/글번호)으로 간다. --%>
			<%-- 글 번호와 사진 주소는 서버가 만든 값이라 그대로 출력해도 안전하다. --%>
			<a class="card" href="/posts/${post.id}">
				<%-- 목록에는 큰 사진 대신 600 × 600 썸네일을 쓴다 (보통 수십 KB라 목록이 빨리 뜬다) --%>
				<%-- loading="lazy": 화면에 가까워졌을 때 사진을 불러온다 (휴대폰 데이터 절약) --%>
				<img src="${post.thumbnailUrl}" alt="음식 사진" loading="lazy">
				<%-- 글자 두 줄 (JM-72). 네 값이 카드마다 같은 자리에 온다.
				     첫 줄: 왼쪽에 닉네임(길면 …으로 줄인다), 오른쪽 끝에 칼로리
				     둘째 줄: 왼쪽에 숫자(좋아요, 댓글, 조회수), 오른쪽 끝에 날짜
				     운영자 검토로 여러 번 옮긴 자리다. 맨 처음에는 닉네임 바로 뒤에 날짜가 붙어서, 닉네임 길이에 따라 날짜 자리가 카드마다 달랐다 --%>
				<div class="card-row">
					<%-- 닉네임은 사용자가 쓴 값이라 반드시 c:out으로 출력한다 (XSS 방지) --%>
					<strong class="card-name"><c:out value="${post.nickname}" /></strong>
					<%-- 칼로리: 분석이 끝난 글만 보여 준다. 분석 중이면 안내, 음식이 아니거나 실패한 글과 분석 기록이 없는 글은 비워 둔다.
					     kcal-unit: 휴대폰에서 단위 글자만 작게 줄이려고 따로 감쌌다 (app.css) --%>
					<c:choose>
						<c:when test="${post.analysisStatus == 'DONE'}"><span class="card-kcal"><fmt:formatNumber value="${post.totalKcal}" maxFractionDigits="0" /> <span class="kcal-unit">kcal</span></span></c:when>
						<c:when test="${post.analysisStatus == 'PENDING'}"><span class="card-pending">분석 중</span></c:when>
					</c:choose>
				</div>
				<div class="card-row card-sub">
					<%-- 숫자: 좋아요, 댓글, 조회수. 0이어도 자리를 지킨다. 아이콘은 app.css가 그린다.
					     sr-only: 화면에는 안 보이고 화면 읽기 프로그램만 읽는 글자 ("좋아요 3") --%>
					<span class="card-stats">
						<span class="stat stat-like"><span class="sr-only">좋아요 </span>${post.likeCount}</span>
						<span class="stat stat-comment"><span class="sr-only">댓글 </span>${post.commentCount}</span>
						<span class="stat stat-view"><span class="sr-only">조회 </span>${post.viewCount}</span>
					</span>
					<%-- 날짜: 두 모양을 넣어 두고 app.css가 하나만 보여 준다. PC는 연도까지 "2026.11.11", 휴대폰은 짧게 "11.11" (지난해 글은 "25.12.31")
					     올린 시각은 글을 열면 상세 화면에서 보인다 --%>
					<span class="card-when"><span class="when-full"><c:out value="${post.createdDate}" /></span><span class="when-short"><c:out value="${post.createdShort}" /></span></span>
				</div>
				<%-- 메모는 목록에 보여 주지 않는다 (JM-72, 운영자 검토): 메모가 있는 카드와 없는 카드의 높이가 달라 격자가 들쑥날쑥해 보였다.
				     메모는 글을 열면 상세 화면에서 보인다. 덕분에 모든 카드가 사진 아래 두 줄로 똑같다 --%>
			</a>
		</c:forEach>
	</div>

	<%-- 쪽 번호 줄: 이전 1 2 3 다음 (JM-57). 2쪽 이상일 때만 보인다. 그리는 법은 common/pager.jspf --%>
	<c:set var="nav" value="${feed.nav}" />
	<c:set var="pagerBase" value="/?page=" />
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
