// 위치 도메인의 VO(값 객체)를 두는 패키지
package backend.location.model.vo;

// VO(값 객체): 한 지점이 들어 있는 행정구역의 이름 세 단계 (JM-85)
// sido: 시/도 (예: 서울특별시)
// sigungu: 시/도를 앞에 붙인 시/군/구 (예: 서울특별시 중구). 중구, 동구처럼 같은 이름이 여러 시/도에 있어서 붙인다
// dong: 시/도와 시/군/구를 앞에 붙인 행정동 (예: 서울특별시 중구 명동). 법정동이 아니라 행정동 이름이다
public record Region(String sido, String sigungu, String dong) {
}
