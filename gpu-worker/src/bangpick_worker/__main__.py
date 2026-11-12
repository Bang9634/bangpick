"""One-shot bootstrap check; no jobs or GPU operations are performed."""

import json


def main() -> None:
    print(json.dumps({
        "service": "bangpick-gpu-worker",
        "status": "scaffold-only",
        "job_processing_enabled": False,
    }))


if __name__ == "__main__":
    main()
