FROM python:3.11-slim-bookworm
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir /work && chown app:app /work
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .
USER app
WORKDIR /work
ENTRYPOINT ["bangpick-worker"]
