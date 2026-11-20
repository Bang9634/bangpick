# GPU Worker

Worker CLI는 지정한 작업 하나를 점유하고, OCI에서 원본 영상을 내려받아 프레임을 추출하거나 로컬 3DGS PLY를 업로드합니다. 현재 명령 실행에는 GPU가 필요하지 않습니다.

Python 3.11 이상에서 `pyproject.toml`이 있는 디렉토리에서 환경을 준비합니다.
호스트에서 전처리를 실행할 때는 `ffmpeg`를 PATH에 추가합니다. Worker 컨테이너 이미지에는 FFmpeg를 설치합니다.

```sh
python -m venv .venv
.venv/bin/python -m pip install -e .
```

Windows에서는 `.venv\Scripts\python.exe -m pip install -e .`를 실행하고, 아래의 `.venv/bin/bangpick-worker` 대신 `.venv\Scripts\python.exe -m bangpick_worker`를 사용합니다.

Worker 실행 환경에 `BANGPICK_API_URL`과 `BANGPICK_MEDIA_WORKER_TOKEN`을 지정합니다. API 주소는 `/api`로 끝나야 하고, 같은 컴퓨터 또는 터널의 루프백 주소만 HTTP를 허용합니다. 예를 들어 맥에서 백엔드를 켜고 윈도우에서 보호된 터널을 통해 맥의 8080으로 연결하면 Worker 주소는 터널의 로컬 포트가 됩니다. 원격 API 주소를 직접 사용하려면 HTTPS가 필요합니다.

[영상 업로드 API](../contracts/README.md#영상ply-전송)가 반환한 작업 UUID를 쓰고, 두 명령에서 같은 작업 폴더를 지정합니다.

```sh
.venv/bin/bangpick-worker preprocess <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker upload <job-id> ./room.ply --work-dir ../outputs/worker
```

`preprocess`는 백엔드가 발급한 임시 URL로 영상을 받은 뒤 `<job-id>/images/`에 초당 6장의 JPG를 원본 크기로 저장합니다. 추출이 끝난 뒤에만 `images/`가 생성되므로 실패하면 같은 명령으로 재시도할 수 있습니다. 이미 완성된 `images/`가 있으면 기존 결과를 보호하기 위해 명령이 중단됩니다. 원본 다운로드만 필요하면 `download <job-id> --work-dir ../outputs/worker`를 사용합니다.

작업 폴더의 `<job-id>/source.*`가 다운로드한 영상이고, `lease` 파일은 다음 요청에 필요한 현재 점유 ID입니다. 작업 폴더·실제 모델·개발용 토큰을 커밋하지 않습니다. 이미 업로드가 끝났는데 완료 응답만 실패했다면 같은 인자로 `complete <job-id> --work-dir ../outputs/worker`를 실행합니다. PLY 업로드가 실패했다면 `upload`를 재실행해 새 URL을 받습니다.

백엔드 API와 Worker는 JSON을 주고받고, 파일은 Worker가 OCI와 HTTPS로 직접 전송합니다. Worker에 OCI 키를 복사하거나 수신 포트를 열 필요는 없습니다. [전송 계약](../contracts/README.md#영상ply-전송) 참고.

## Worker 테스트

패키지를 설치한 뒤 `gpu-worker/`에서 아래 명령을 실행합니다. 백엔드·OCI 응답과 COLMAP·Nerfstudio 실행은 테스트에서 재현하므로 GPU나 계정 설정 없이 확인할 수 있습니다. FFmpeg가 PATH에 있으면 테스트 영상으로 실제 JPG 추출도 실행하며, 없으면 실제 추출 테스트 3개를 건너뜁니다.

```sh
.venv/bin/python -m unittest discover -s tests -v
```

Windows에서는 `.venv\Scripts\python.exe -m unittest discover -s tests -v`를 사용합니다.

| 파일 | 확인하는 내용 |
| --- | --- |
| `tests/test_transfers.py` | 파일 크기, 업로드 바이트, 토큰 전달 범위 |
| `tests/test_preprocess.py` | 전처리 실패·정리, FPS 전달, 실제 FFmpeg 추출 |
| `tests/test_colmap_poses.py` | 매칭 방식, 모델 선택, 개수 헤더, 실패·정리 |
| `tests/test_train_3dgs.py` | 20,000스텝 기본값, 선택 모델 전달, 체크포인트, PLY 검증 |
| `tests/test_main.py` | 명령 인자, 단계 실행 순서, 결과 전달, 업로드·완료 보고 재시도 |

실제 COLMAP·Nerfstudio·CUDA 호환성과 장면 품질은 GPU 환경에서 별도로 검증합니다. 현재 `reconstruct`의 처리 단계 재개와 COLMAP 품질 기준은 추가 구현 과제입니다.

직접 촬영한 로컬 영상은 `bangpick_worker.preprocess.extract_frames(Path("./clip.mp4"), Path("../outputs/local-test/images"))`에 넘겨 확인할 수 있습니다. 이 함수는 백엔드 환경 변수 없이 실행됩니다.
