# GPU Worker 초기 패키지

Python 3.11 이상용 패키지와 `bangpick-worker` 실행 진입점만 제공합니다. 기본 Docker 이미지는 Python 3.11을 사용합니다. 작업 조회·파일 전송·전처리·학습·후처리는 아직 구현하지 않았습니다. 현재 실행은 scaffold 상태를 한 번 출력하고 정상 종료합니다.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -e .
.venv/bin/bangpick-worker
```

명령은 이 디렉토리에서 실행합니다. Windows는 `.venv\Scripts\python.exe`와 해당 scripts 경로를 사용합니다. 의존성은 아직 없으며 실제 학습 도구·CUDA 환경을 정한 뒤 별도로 명세합니다. 기본 실행에 GPU가 필요한 것은 아닙니다. 컨테이너 실행은 [인프라 안내](../infra/README.md)를 참고합니다.
