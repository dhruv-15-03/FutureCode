FROM python:3.13-slim
WORKDIR /app
COPY ops/cost_guard/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
RUN groupadd --gid 10001 futurecode && useradd --uid 10001 --gid futurecode --no-create-home --shell /usr/sbin/nologin futurecode
COPY --chown=10001:10001 ops/cost_guard/main.py ops/cost_guard/guard.py ops/cost_guard/gcp_backend.py ops/cost_guard/policy.json ./
USER 10001:10001
ENV PORT=8080
EXPOSE 8080
CMD ["python", "-m", "functions_framework", "--target=protect_futurecode", "--signature-type=http", "--port=8080"]
