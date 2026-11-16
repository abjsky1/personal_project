<%@ tag pageEncoding="UTF-8" body-content="empty" %>
<%-- 관리자 통계의 비율 한 칸 (JM-75): "66.7% (10 / 15)". 나눌 것이 없어 비율이 비어 있으면(null) "-" --%>
<%@ attribute name="rate" type="java.lang.Double" required="false" %>
<%@ attribute name="part" type="java.lang.Long" required="true" %>
<%@ attribute name="whole" type="java.lang.Long" required="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:choose><c:when test="${empty rate}">-</c:when><c:otherwise>${rate}%</c:otherwise></c:choose> <span class="stats-frac">(${part} / ${whole})</span>