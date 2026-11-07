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
	<header class="top top-home">
		<div>
			<h1>찍먹</h1>
			<%-- 상단 메뉴: 피드(이 화면)와 피드백 게시판을 오간다 --%>
			<nav class="site-nav">
				<a href="/" class="active">피드</a>
				<a href="/feedback">피드백</a>
			</nav>
		</div>
		<%-- 한 줄 소개: 처음 온 사람이 뭘 하는 곳인지 바로 알게. PC에서는 제목과 버튼 사이 가운데, 휴대폰에서는 둘째 줄 (app.css의 .top-home) --%>
		<p class="slogan">찍고 먹자 !<small>먹은 사진을 올리면 AI가 칼로리를 알려 줘요</small></p>
		<a class="button" href="/posts/new">사진 올리기</a>
	</header>

	<%-- empty: 목록이 비어 있으면 true --%>
	<c:if test="${empty feed.posts}">
		<p class="empty">아직 올라온 사진이 없어요.</p>
	</c:if>

	<%-- 카드 격자: PC에서는 한 줄에 여러 장, 휴대폰에서는 한 줄에 두 장 (칸 수는 app.css의 .feed가 화면 폭을 보고 정한다. 두 장은 지인 피드백 3번, JM-50) --%>
	<div class="feed">
		<%-- c:forEach: feed.posts의 글을 하나씩 post라는 이름으로 꺼내 반복한다. --%>
		<c:forEach var="post" items="${feed.posts}">
			<%-- 카드 전체가 링크라서 사진이나 글씨 아무 곳을 눌러도 상세 화면(/posts/글번호)으로 간다. --%>
			<%-- 글 번호와 사진 주소는 서버가 만든 값이라 그대로 출력해도 안전하다. --%>
			<a class="card" href="/posts/${post.id}">
				<%-- 목록에는 큰 사진 대신 600 × 600 썸네일을 쓴다 (보통 수십 KB라 목록이 빨리 뜬다) --%>
				<%-- loading="lazy": 화면에 가까워졌을 때 사진을 불러온다 (휴대폰 데이터 절약) --%>
				<img src="${post.thumbnailUrl}" alt="음식 사진" loading="lazy">
				<%-- 분석이 끝난 글은 칼로리, 분석 중인 글은 안내를 보여 준다. 음식이 아니거나 실패한 글, 분석 기록이 없는 글은 아무것도 보여 주지 않는다. --%>
				<c:choose>
					<c:when test="${post.analysisStatus == 'DONE'}">
						<p class="card-kcal"><fmt:formatNumber value="${post.totalKcal}" maxFractionDigits="0" /> kcal</p>
					</c:when>
					<c:when test="${post.analysisStatus == 'PENDING'}">
						<p class="meta">칼로리 분석 중</p>
					</c:when>
				</c:choose>
				<%-- 닉네임·메모는 사용자가 쓴 값이라 반드시 c:out으로 출력한다 (XSS 방지) --%>
				<%-- 댓글 수는 있을 때만 보여 준다 (0개는 표시하지 않는다) --%>
				<p class="meta">
					<strong><c:out value="${post.nickname}" /></strong> · <c:out value="${post.createdAt}" />
					<c:if test="${post.commentCount > 0}"> · 댓글 ${post.commentCount}</c:if>
				</p>
				<c:if test="${not empty post.memo}">
					<p class="memo"><c:out value="${post.memo}" /></p>
				</c:if>
			</a>
		</c:forEach>
	</div>

	<%-- 더 볼 글이 있을 때만 "더 보기" 링크를 보여 준다. 누르면 마지막 글 번호(cursor) 다음부터 이어서 보여 준다. --%>
	<c:if test="${feed.nextCursor != null}">
		<a class="more" href="/?cursor=${feed.nextCursor}">더 보기</a>
	</c:if>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
