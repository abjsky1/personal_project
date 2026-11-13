<%-- 공지 목록 (JM-65): 게임 공지 게시판처럼 한 줄에 [안내] 제목, 오른쪽에 조회수와 날짜. 누르면 상세 --%>
<%-- 공지 글은 src/main/resources/notices.json에 있다. 새 공지는 그 파일 맨 위에 더한다 (기획서 16절) --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
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

	<c:if test="${empty page.notices}">
		<p class="empty">아직 공지가 없어요.</p>
	</c:if>

	<%-- 공지 한 줄: 맨 왼쪽에 공지 번호 (JM-76, 운영자 요청), 그다음 [분류] 제목, 오른쪽에 조회수와 날짜.
	     줄 전체가 링크라서 어디를 눌러도 상세로 간다. 제목은 우리가 쓴 글이지만 다른 화면처럼 c:out으로 찍는다.
	     번호는 notices.json의 id이고 주소 /notice/번호의 번호와 같다.
	     지운 공지(그 번호가 notices.json에 없다)도 줄을 빼지 않는다: 번호는 그대로 두고, 링크 없이 "삭제된 공지입니다."만 보여 준다.
	     → 공지를 지워도 다른 공지의 번호와 자리가 당겨지지 않는다 --%>
	<ul class="notice-list">
		<c:forEach var="notice" items="${page.notices}">
			<li>
				<c:choose>
					<c:when test="${notice.deleted}">
						<div class="notice-row">
							<span class="list-no">${notice.id}</span>
							<span class="list-deleted">삭제된 공지입니다.</span>
						</div>
					</c:when>
					<c:otherwise>
						<a class="notice-row" href="/notice/${notice.id}">
							<span class="list-no">${notice.id}</span>
							<span class="notice-title">[<c:out value="${notice.category}" />] <c:out value="${notice.title}" /></span>
							<span class="notice-meta"><span>조회 <fmt:formatNumber value="${notice.views}" /></span><span>${notice.date}</span></span>
						</a>
					</c:otherwise>
				</c:choose>
			</li>
		</c:forEach>
	</ul>

	<%-- 쪽 번호 줄: 피드, 피드백 목록과 같은 common/pager.jspf. 공지가 20개를 넘어 2쪽 이상일 때만 보인다 --%>
	<c:set var="nav" value="${page.nav}" />
	<c:set var="pagerBase" value="/notice?page=" />
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
