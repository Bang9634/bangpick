# 설계 시각 자료

설계 기준일: 2026-11-12. 그림은 목표 구조이며 기능 구현 완료나 성능 검증을 의미하지 않습니다. 설계 해설은 [시스템 설계](../architecture.md), 현재 구현 상태는 [개발 안내](../development.md)에 기록합니다.

| 자료 | 편집 원본 | 웹·문서용 SVG | 보고서·슬라이드용 PNG |
| --- | --- | --- | --- |
| 전체 시스템 구성 | [DOT](system-overview.dot) | [SVG](system-overview.svg) | [PNG](system-overview.png) |
| 업로드·재구성·검수 흐름 | [DOT](reconstruction-flow.dot) | [SVG](reconstruction-flow.svg) | [PNG](reconstruction-flow.png) |
| 스토리지 의존성 분리 | [DOT](storage-boundary.dot) | [SVG](storage-boundary.svg) | [PNG](storage-boundary.png) |

## 편집·재생성

Graphviz의 `dot`과 한국어 글꼴이 필요합니다. 그림 생성 도구는 서비스 실행 의존성이 아닙니다. 이 작업에서는 Graphviz 16.1.0으로 출력했으며, 운영체제·글꼴·버전에 따라 배치가 달라질 수 있습니다. 한국어 글꼴로 macOS의 Apple SD Gothic Neo 또는 Linux의 Noto Sans CJK KR 등을 사용할 수 있습니다.

저장소 루트에서 실행합니다.

```sh
sh docs/diagrams/render.sh
```

글꼴을 명시하려면 다음처럼 실행합니다.

```sh
DIAGRAM_FONT='Noto Sans CJK KR' sh docs/diagrams/render.sh
```

DOT가 편집 원본입니다. SVG·PNG를 직접 수정하지 말고 원본 수정 후 함께 재생성합니다. SVG는 확대·인쇄용 벡터 형식이며 PNG는 160 DPI로 출력합니다. SVG의 글꼴 표시는 열람 환경에 영향을 받으므로 동일한 모양을 보존하려면 PNG를 사용합니다.

## 관리 기준

- 문서에서는 이 디렉토리의 SVG를 링크합니다. 같은 그림을 README·Notion·보고서용으로 여러 원본에 나누지 않습니다.
- 구조가 바뀌면 원본·출력물·관련 설계 설명을 같은 변경에서 갱신합니다. 범례와 설계 기준일도 확인합니다.
- 보고서 제출본은 해당 Git 커밋과 함께 식별합니다. 문서와 다른 날짜의 그림을 섞지 않습니다.
- 실측 성능 그래프와 실제 화면은 해당 `docs/experiments/` 실험 기록에서 관리합니다. 목표 설계 그림과 측정 결과를 구분합니다.
- 실제 키·토큰·원본 영상·개인 PC 주소·로컬 경로는 그림에 포함하지 않습니다.
