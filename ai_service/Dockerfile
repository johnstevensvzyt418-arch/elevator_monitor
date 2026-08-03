# ============================================================
# Elevator Monitor — AI 异常检测服务 Dockerfile
# 构建: docker build -t elevator-ai .
# 运行: docker run -p 8000:8000 elevator-ai
# ============================================================

FROM python:3.11-slim

WORKDIR /app

# 当前生产评分后端是轻量协议基线，不需要安装 PyTorch。
# legacy_lstm 仍由 infer_server.py 延迟加载，但需要使用者在自定义镜像中另行安装 torch。
COPY requirements.txt .
RUN pip install --no-cache-dir -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt

# 复制所有模型文件
COPY config.toml .
COPY infer_server.py .
COPY calibrate_protocol.py .
COPY calibrate_runtime_log.py .
COPY protocol_baseline.py .
COPY model/ ./model/
COPY trained_models/ ./trained_models/

EXPOSE 8000

# 启动 FastAPI 服务
CMD ["uvicorn", "infer_server:app", "--host", "0.0.0.0", "--port", "8000"]
