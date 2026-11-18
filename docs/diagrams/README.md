# 시각 자료

각 그림은 [시스템 설계](../architecture.md)나 서비스 간 계약의 해당 흐름을 설명합니다.

| 그림 | 범위 | 편집 원본 | 웹용 | 보고서용 |
| --- | --- | --- | --- | --- |
| 전체 시스템 구성 | 서비스 설계의 구성 요소와 연결 | [DOT](system-overview.dot) | [SVG](system-overview.svg) | [PNG](system-overview.png) |
| 업로드부터 투어 공개까지 | 서비스 설계의 재구성·검수·공개 | [DOT](reconstruction-flow.dot) | [SVG](reconstruction-flow.svg) | [PNG](reconstruction-flow.png) |
| OCI 영상·PLY 전송 | 현재 개발 환경의 API 요청과 실제 파일 이동 | [DOT](oci-transfer-flow.dot) | [SVG](oci-transfer-flow.svg) | [PNG](oci-transfer-flow.png) |
| 재구성 작업 큐 | [계약 초안](../../contracts/worker-queue.md)의 첫 통합 범위 | [DOT](worker-queue-flow.dot) | [SVG](worker-queue-flow.svg) | [PNG](worker-queue-flow.png) |
| 스토리지 의존성 경계 | 백엔드 코드의 인터페이스와 OCI 어댑터 | [DOT](storage-boundary.dot) | [SVG](storage-boundary.svg) | [PNG](storage-boundary.png) |

## 편집과 재생성

Graphviz `dot`과 한국어 글꼴을 설치하고 저장소 루트에서 실행합니다.

```sh
sh docs/diagrams/render.sh
```

`DIAGRAM_FONT='Noto Sans CJK KR'`처럼 글꼴을 지정할 수 있습니다. **DOT가 원본**이며 수정할 때 SVG와 PNG를 함께 다시 생성합니다. PNG는 160 DPI로, 보고서에서 글꼴 모양을 유지할 때 사용합니다. 보고서에 인용할 때 그림의 범위와 저장소 커밋을 함께 기록합니다.
