# 0003 — 대표 영상으로 Worker 큐 실행과 등록 사진 부족 시 동작 확인

- 날짜: 2026-11-22
- 작성자/실행자: skdks2525 / skdks2525
- 상태: 실패 (대표 영상의 PLY 생성 목표 기준)
- 증거: 직접 실행. 테스트 출력에서 등록 사진 수와 실패 보고를 확인하고, 작업 폴더에서 JPG와 PLY 개수를 확인했다.

## 목적과 판정 기준

대표 영상 한 건을 기본 순차 매칭으로 처리할 때 `process-next`가 작업을 점유하고, 생성된 PLY를 업로드 대상으로 전달한 뒤 완료를 보고하는지 확인한다. 성공 조건은 해당 점유의 `room.ply` 생성, 업로드 함수 호출, `result-complete` 보고다. 중간에 멈추면 마지막 실행 단계와 백엔드에 보고한 상태를 기록한다.

## 입력과 재현 환경

| 항목 | 실행 조건 |
| --- | --- |
| 입력 | [실험 0002](0002-colmap-registration-criteria.md)와 같은 실내 재촬영 영상 1건. 6 FPS로 1080×1920 JPG 추출 |
| 환경 | Windows, NVIDIA GeForce RTX 3070 Ti 8GB, FFmpeg 9.0.2, COLMAP 3.11.1 CUDA |
| 매칭·등록 기준 | GPU 순차 매칭, 매칭 상한 12,000. **실행 당시** 선택 모델에 100장 이상, 전체 입력의 50% 이상 등록 필요 |
| 코드 | `feat/worker-pipeline-integration`, 기준 커밋 `ffb40c7`과 로컬에서 추가한 [대표 영상 테스트](../../gpu-worker/tests/test_process_next.py) |
| 실제 실행 범위 | Worker CLI, supervisor의 자식 프로세스, FFmpeg, COLMAP. 백엔드 응답은 테스트 대역을 사용하고 OCI 다운로드는 로컬 영상 복사로 대체 |

## 실행 방법

GPU 학습 환경을 활성화하고 `gpu-worker/`에서 실행한다. 아래 경로는 재현용 예시다. FFmpeg와 COLMAP이 같은 터미널의 PATH에서 실행되는지 먼저 확인한다.

```powershell
$env:PYTHONPATH = (Resolve-Path src).Path
$env:BANGPICK_RUN_GPU_TEST = '1'
$env:BANGPICK_TEST_VIDEO = (Resolve-Path '../inputs/room-01.mp4').Path
$env:BANGPICK_TEST_WORK_DIR = Join-Path (Get-Location) '../runs'
python -m unittest discover -s tests -p test_process_next.py -k test_process_next_builds_scene_from_local_video -v
```

테스트용 백엔드가 작업을 배정하면 영상이 작업별 `source.mp4`로 복사된다. `process-next`는 supervisor를 통해 `python -m bangpick_worker build-3d-scene`을 실행한다. JPG는 점유별 `attempts/<lease-id>/images/`에 저장되며 성공 시 같은 점유 폴더에 `room.ply`가 생긴다.

## 결과

| 시도 (작업 ID 앞 8자리) | 마지막으로 확인한 동작 | 실행 결과 |
| --- | --- | --- |
| 1차 · `6edcec24` | 작업 점유 및 영상 복사 | 자식 프로세스가 PATH에서 FFmpeg를 찾지 못해 종료. JPG 0장 |
| 2차 · `8e2c5336` | FFmpeg 경로 등록 후 JPG 495장 추출, COLMAP 특징점 추출 | GPU 순차 매칭이 첫 이미지 처리 로그 직후 종료(`0xC0000409`). 원인 미확정 |
| 3차 · `7220f33a` | 동일한 GPU 설정으로 재실행. 순차 매칭과 카메라 위치 추정 완료 | 선택 모델에 **495장 중 2장(0.4%)** 등록. 학습 시작 기준 미달로 중단. 테스트 총 **27.27초** |

3차 시도에서 자식 프로세스는 종료 코드 1을 반환했고, 부모 Worker는 `processing-failed`를 보고했다. 테스트용 백엔드의 최종 상태는 `FAILED`였다. 세 작업 모두 PLY는 0개였고, 3DGS 학습·업로드·완료 보고에 도달하지 않았다.

## 해석·한계·다음 검증

- 대표 영상으로 큐 점유부터 전처리·COLMAP·등록 기준 검사·실패 보고까지 관찰했다. 2차에서 발생한 매칭 오류는 3차에서 재현되지 않았다.
- 등록 기준은 이후 100장·20%로 변경됐다. 이번 영상은 변경한 기준으로도 최소 100장이 필요하므로 2장 등록 결과는 여전히 미달이다.
- [실험 0002](0002-colmap-registration-criteria.md)의 순차 20장과 전수 283장은 별도 매칭 DB로 mapper를 실행한 결과다. 다음 검증에서는 큐에서 전수 매칭을 선택하고, **한 작업에서** 생성한 PLY가 업로드 대상으로 전달되고 완료 보고까지 이어지는지 확인한다. 실제 OCI 객체 저장과 백엔드 상태 전환은 서버 통합 환경에서 별도로 확인한다.
