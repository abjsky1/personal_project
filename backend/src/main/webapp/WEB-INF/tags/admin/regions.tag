<%@ tag pageEncoding="UTF-8" body-content="empty" %>
<%-- 지역별 글 수 표 하나 (JM-85): 제목, 글이 있는 곳의 수, 글이 많은 순 10곳 (AdminStatsResponse.RegionLevel) --%>
<%@ attribute name="title" required="true" %>
<%@ attribute name="level" type="java.lang.Object" required="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<div class="stats-region">
	<h3>${title} <span class="stats-frac">${level.areas}곳</span></h3>
	<c:choose>
		<c:when test="${empty level.top}"><p class="stats-note">아직 없습니다</p></c:when>
		<c:otherwise>
			<table class="stats-table">
				<c:forEach var="row" items="${level.top}">
					<tr><th><c:out value="${row.name}" /></th><td>${row.count}</td></tr>
				</c:forEach>
			</table>
		</c:otherwise>
	</c:choose>
</div>
