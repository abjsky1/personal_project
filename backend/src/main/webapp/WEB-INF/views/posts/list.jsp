<%-- 게시글 목록 (첫 화면). 검색 결과(/search?q=, JM-52)도 이 화면으로 그린다: query가 있으면 검색 결과다 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<c:choose>
	<%-- 검색어는 사용자가 친 값이라 c:out으로 출력한다 (XSS 방지) --%>
	<c:when test="${not empty query}"><title>'<c:out value="${query}" />' 검색 - 찍먹</title>
	<%-- 검색 결과 화면은 검색 엔진에 올리지 않는다 (검색어마다 비슷한 화면이 끝없이 생긴다) --%>
	<meta name="robots" content="noindex"></c:when>
	<c:otherwise><title>찍먹</title></c:otherwise>
</c:choose>
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
			<%-- 검색 결과도 피드 안에서 찾은 것이라 "피드"를 초록으로 둔다. 다만 지금 화면이 피드 그 자체는 아니라서 aria-current는 피드에만 --%>
			<a href="/" class="active"<c:if test="${empty query}"> aria-current="page"</c:if>>피드</a>
			<a href="/notice">공지</a>
			<a href="/feedback">개발자에게 피드백 보내기</a>
		</nav>
		<%-- 검색 칸 (JM-52, 피드백 6번과 11번): 닉네임이나 음식 이름으로 찾는다. 메모는 찾지 않는다.
		     PC는 메뉴 링크와 같은 줄 오른쪽, 휴대폰은 메뉴 링크 아래 한 줄 전체 (자리는 app.css의 .top-menu .search)
		     method="get": 검색어가 주소(/search?q=라면)에 남아서 뒤로 가기, 새로 고침, 주소 공유가 된다
		     type="search": 휴대폰 자판의 "검색" 버튼으로 바로 보내고, 칸 안의 지우기(x)가 생긴다
		     maxlength="30": 서버도 30자까지만 쓴다 (PostService.MAX_QUERY_LENGTH) --%>
		<form class="search" action="/search" method="get" role="search">
			<input type="search" name="q" value="<c:out value='${query}' />" maxlength="30" placeholder="닉네임이나 음식 이름" aria-label="검색어 (닉네임이나 음식 이름)">
			<button type="submit">찾기</button>
		</form>
	</header>

	<%-- 검색 결과 줄 (JM-52): 무엇으로 찾았는지, 몇 개인지, 피드로 돌아가는 링크 --%>
	<c:if test="${not empty query}">
		<p class="search-result">'<strong><c:out value="${query}" /></strong>' 검색 결과 ${total}개 <a href="/">전체 보기</a></p>
	</c:if>

	<%-- empty: 목록이 비어 있으면 true --%>
	<c:if test="${empty feed.posts}">
		<c:choose>
			<c:when test="${not empty query}"><p class="empty search-empty">찾은 글이 없어요.<br>음식 이름의 일부만 쳐 보세요 (예: 찌개)</p></c:when>
			<c:otherwise><p class="empty">아직 올라온 사진이 없어요.</p></c:otherwise>
		</c:choose>
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
				<%-- 사진 검토 (JM-80): AI가 음식이 아니라고 본 글은 운영자가 올리기 전까지 사진 대신 안내를 보여 준다.
				     사진 주소를 쓰지 않아서 페이지 소스에도 남지 않는다. 자리는 썸네일과 같은 정사각형이다 (app.css의 .photo-held) --%>
				<c:choose>
					<c:when test="${post.photoHeld}"><span class="photo-held card-held">관리자가 사진을 검토 중입니다</span></c:when>
					<c:otherwise><img src="${post.thumbnailUrl}" alt="음식 사진" loading="lazy"></c:otherwise>
				</c:choose>
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
	<%-- 검색 결과는 쪽을 넘겨도 같은 검색어로 (/search?q=라면&page=2). &amp;는 HTML 안의 & (브라우저가 &로 읽는다) --%>
	<c:choose>
		<c:when test="${not empty query}"><c:set var="pagerBase" value="/search?q=${searchParam}&amp;page=" /></c:when>
		<c:otherwise><c:set var="pagerBase" value="/?page=" /></c:otherwise>
	</c:choose>
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
