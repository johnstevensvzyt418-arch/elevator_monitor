# ============================================================
# Elevator Monitor — AI 异常检测服务 Dockerfile
# 构建: docker build -t elevator-ai .
# 运行: docker run -p 8000:8000 elevator-ai
# ============================================================

FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖（libgomp1 是 PyTorch 运行时需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir --no-deps --no-index --find-links https://mirrors.aliyun.com/pytorch-wheels/cpu/ torch
RUN pip install --no-cache-dir -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt

# 复制所有模型文件
COPY config.toml .
COPY infer_server.py .
COPY protocol_baseline.py .
COPY model/ ./model/
COPY trained_models/ ./trained_models/

EXPOSE 8000

# 启动 FastAPI 服务
CMD ["uvicorn", "infer_server:app", "--host", "0.0.0.0", "--port", "8000"]
