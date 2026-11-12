# 인프라 초기 설정

루트 `compose.yaml`은 로컬에서 frontend·backend·MySQL을 실행합니다. 외부 공개·TLS·Oracle 버킷·운영 배포 자동화는 포함하지 않습니다. 로컬 포트는 루프백에만 바인딩합니다.

```sh
cp .env.example .env
# .env의 두 비밀번호를 변경한 뒤 실행
docker compose up --build -d
docker compose ps
docker compose down
```

명령은 저장소 루트에서 실행합니다. `docker compose down`은 DB 볼륨을 유지합니다. `down -v`는 DB 데이터를 삭제하므로 초기화가 필요한 경우에만 사용합니다. 비밀번호는 최초 DB 초기화에 사용되므로 기존 볼륨이 있는 상태에서 .env만 바꾼다고 DB 비밀번호가 변경되지는 않습니다.

Worker 초기 패키지는 GPU 없이 단독 확인할 수 있습니다. 한 번 상태를 출력하고 종료하며 작업을 대기·학습하는 서비스가 아닙니다.

```sh
docker compose -f infra/compose.worker.yaml run --build --rm worker
```

Linux NVIDIA GPU 장비에서 GPU 할당 설정을 추가하는 명령은 다음과 같습니다.

```sh
docker compose -f infra/compose.worker.yaml -f infra/compose.worker.gpu.yaml run --build --rm worker
```

GPU override는 GPU 접근 설정만 추가합니다. NVIDIA 호스트 드라이버·Container Toolkit이 필요하며, 이미지에는 CUDA·PyTorch·COLMAP·Nerfstudio가 아직 없습니다. 실제 학습 환경은 팀의 재현 실험을 기준으로 별도 이미지와 버전을 정합니다. Worker의 작업 볼륨은 임시 작업 공간이며 공통 오브젝트 스토리지를 대체하지 않습니다.
