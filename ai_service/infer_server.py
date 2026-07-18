"""
Elevator Monitor — AI 异常检测推理服务 (FastAPI)

启动: uvicorn infer_server:app --host 0.0.0.0 --port 8000
"""

import os
import sys
from contextlib import asynccontextmanager

import numpy as np
import tomli
import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# ---- 确保 ai_service 目录在 sys.path 中，以便 import model 子包 ----
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from model.LSTM_AE import LSTM_AE
from model.CNN_AE import CNN_AE
from model.CNN_LSTM_AE import CNN_LSTM_AE
from model.MLP_AE import MLP_AE
from model.Transformer import TransformerAE
from protocol_baseline import DISPLAY_THRESHOLD, ProtocolBaselineScorer

# ============================================================
# 全局变量：启动时加载
# ============================================================
model = None
device = None
mu = None
cov_matrix_inv = None
threshold = 90.0
score_mode = "mean"
model_type = "LSTM_AE"
scoring_backend = "legacy_lstm"
feature_schema = "legacy-v1"
baseline_scorer = None

# ============================================================
# 请求/响应模型
# ============================================================

class PredictRequest(BaseModel):
    """推理请求"""
    deviceId: str                          # 设备ID（用于日志追踪）
    sequence: list[list[float]]            # 二维数组 [时间长度, 5]
    schemaVersion: str = "legacy-v1"       # 防止不同协议特征被误送给模型


class PredictResponse(BaseModel):
    """推理响应"""
    deviceId: str
    score: float
    threshold: float
    is_abnormal: bool
    label: str                             # "normal" 或 "abnormal"
    schema_version: str


class HealthResponse(BaseModel):
    status: str
    model_type: str
    threshold: float
    device: str
    scoring_backend: str
    schema_version: str
    calibration_count: int


# ============================================================
# 模型加载
# ============================================================

def load_model():
    """根据 config.toml 加载模型和评分参数。"""
    global model, device, mu, cov_matrix_inv, threshold, score_mode, model_type
    global scoring_backend, feature_schema, baseline_scorer

    config_path = os.path.join(os.path.dirname(__file__), "config.toml")
    with open(config_path, "rb") as f:
        config = tomli.load(f)

    deploy_cfg = config.get("Deploy", {})
    scoring_backend = deploy_cfg.get("scoring_backend", "legacy_lstm")
    feature_schema = deploy_cfg.get("feature_schema", "legacy-v1")
    model_type = deploy_cfg.get("model_type", "LSTM_AE")
    threshold = float(deploy_cfg.get("threshold", 90.0))
    score_mode = deploy_cfg.get("score_mode", "mean")
    scoring_params_file = deploy_cfg.get("scoring_params_file", "")
    use_saved = deploy_cfg.get("use_saved_scoring_params", True)

    if scoring_backend == "protocol_baseline":
        baseline_file = deploy_cfg.get("baseline_file", "")
        if not baseline_file:
            raise ValueError("baseline_file is required for protocol_baseline")
        baseline_path = os.path.join(os.path.dirname(__file__), baseline_file)
        if not os.path.exists(baseline_path):
            raise FileNotFoundError(f"协议基线文件不存在: {baseline_path}")
        baseline_scorer = ProtocolBaselineScorer(baseline_path, score_mode)
        if baseline_scorer.schema_version != feature_schema:
            raise ValueError(
                f"配置特征版本 {feature_schema} 与基线 {baseline_scorer.schema_version} 不一致"
            )
        model = None
        device = "cpu"
        model_type = "MNK_PROTOCOL_BASELINE"
        threshold = DISPLAY_THRESHOLD
        score_mode = baseline_scorer.score_mode
        print(
            f"[AI] MNK 协议基线加载完成: schema={feature_schema}, "
            f"normal_rows={baseline_scorer.calibration_count}, "
            f"raw_threshold={baseline_scorer.raw_threshold:.4f}"
        )
        return

    if scoring_backend != "legacy_lstm":
        raise ValueError(f"Unknown scoring_backend: {scoring_backend}")

    device = "cuda:0" if torch.cuda.is_available() else "cpu"

    # ---- 实例化模型 ----
    if model_type == "LSTM_AE":
        cfg = config["LSTM_AE"]
        model = LSTM_AE(cfg["n_features"], cfg["hidden_size"], cfg["num_layers"], cfg["dropout"])
        weight_file = deploy_cfg.get("weight_file",
                                      "trained_models/xinshida/LSTM_AE-128-1-2026-06-29-16-43.pth")
    elif model_type == "MLP_AE":
        cfg = config["MLP_AE"]
        model = MLP_AE(cfg["input_size"], cfg["hidden_size"], cfg["dropout"])
        weight_file = deploy_cfg.get("weight_file",
                                      "trained_models/xinshida/MLP_AE-1024-1-2025-01-12-02-42.pth")
    elif model_type == "CNN_LSTM_AE":
        cfg = config["CNN_LSTM_AE"]
        model = CNN_LSTM_AE(cfg["n_features"], cfg["hidden_size"],
                            cfg["num_layers"], cfg["out_channels"])
        weight_file = deploy_cfg.get("weight_file",
                                      "trained_models/xinshida/CNN_LSTM_AE-256-2-2026-06-21-22-25.pth")
    elif model_type == "CNN_AE":
        cfg = config["CNN_AE"]
        model = CNN_AE(cfg["input_size"], cfg["hidden_channels"])
        weight_file = deploy_cfg.get("weight_file",
                                      "trained_models/xinshida/CNN_AE-0-1-2026-06-22-16-15.pth")
    elif model_type == "Transformer_AE":
        cfg = config["Transformer_AE"]
        model = TransformerAE(cfg["embed_dim"], cfg["num_heads"], cfg["max_seq_len"],
                              cfg["num_layers"], cfg["n_features"], cfg["dropout"])
        weight_file = deploy_cfg.get("weight_file",
                                      "trained_models/xinshida/Transformer_AE-0-2-2026-06-22-16-58.pth")
    else:
        raise ValueError(f"Unknown model_type: {model_type}")

    # ---- 加载权重 ----
    weight_path = os.path.join(os.path.dirname(__file__), weight_file)
    if not os.path.exists(weight_path):
        raise FileNotFoundError(f"模型权重文件不存在: {weight_path}")
    model.load_state_dict(torch.load(weight_path, map_location=device, weights_only=True))
    model = model.to(device)
    model.eval()

    # ---- 加载评分参数 ----
    if use_saved and scoring_params_file:
        sp_path = os.path.join(os.path.dirname(__file__), scoring_params_file)
        if os.path.exists(sp_path):
            params = np.load(sp_path)
            mu = params["mu"]
            cov_matrix_inv = params["cov_matrix_inv"]
            print(f"[AI] 已加载评分参数: {sp_path}")
        else:
            raise FileNotFoundError(f"评分参数文件不存在: {sp_path}")
    else:
        raise RuntimeError("当前仅支持 use_saved_scoring_params=true 模式")

    print(f"[AI] 模型加载完成: {model_type} on {device}")
    print(f"[AI] 阈值: {threshold:.1f}  评分模式: {score_mode}")


# ============================================================
# 推理逻辑
# ============================================================

def aggregate_scores(timestep_scores: np.ndarray, mode: str) -> float:
    """聚合每个时间步的异常分数。"""
    if mode == "mean":
        return float(np.mean(timestep_scores))
    if mode == "p95":
        return float(np.percentile(timestep_scores, 95))
    if mode == "top10_mean":
        top_n = max(1, int(np.ceil(len(timestep_scores) * 0.1)))
        return float(np.mean(np.sort(timestep_scores)[-top_n:]))
    if mode == "max":
        return float(np.max(timestep_scores))
    raise ValueError(f"Unknown score_mode: {mode}")


def compute_anomaly_score(sequence: np.ndarray) -> float:
    """
    计算单个序列的异常分数。
    
    Args:
        sequence: numpy array, shape [time_length, 5]
    
    Returns:
        anomaly_score: float
    """
    if scoring_backend == "protocol_baseline":
        if baseline_scorer is None:
            raise RuntimeError("protocol baseline is not loaded")
        normalized_score, _ = baseline_scorer.score(sequence)
        return normalized_score

    # 转为 tensor [1, time_length, 5]
    x = torch.tensor(sequence, dtype=torch.float32).unsqueeze(0).to(device)

    with torch.no_grad():
        reconstructed = model(x).cpu().numpy()
        original = x.cpu().numpy()
        errors = original - reconstructed  # [1, time_length, 5]

        timestep_scores = []
        for e_t in errors[0]:  # 遍历每个时间步
            diff = e_t - mu
            score = diff.T @ cov_matrix_inv @ diff
            timestep_scores.append(score)

    return aggregate_scores(np.array(timestep_scores), score_mode)


# ============================================================
# FastAPI 应用
# ============================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动时加载模型，关闭时清理。"""
    print("[AI] 正在加载模型...")
    load_model()
    print("[AI] 服务就绪")
    yield
    print("[AI] 服务关闭")


app = FastAPI(
    title="Elevator AI Anomaly Detection",
    description="LSTM-AE 电梯异常检测推理服务",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查。"""
    calibration_count = baseline_scorer.calibration_count if baseline_scorer else 0
    return HealthResponse(
        status="ok",
        model_type=model_type,
        threshold=threshold,
        device=device,
        scoring_backend=scoring_backend,
        schema_version=feature_schema,
        calibration_count=calibration_count,
    )


@app.post("/predict", response_model=PredictResponse)
async def predict(req: PredictRequest):
    """
    异常检测推理。
    
    输入示例:
    ```json
    {
      "deviceId": "00000004",
      "sequence": [
        [0, 1, 0, 3, 0],
        [0, 1, 0, 3, 0],
        [0, 1, 0, 4, 0]
      ]
    }
    ```
    """
    seq = np.array(req.sequence, dtype=np.float32)

    if req.schemaVersion != feature_schema:
        raise HTTPException(
            status_code=400,
            detail=(
                f"特征版本不匹配: request={req.schemaVersion}, "
                f"expected={feature_schema}"
            ),
        )

    # 校验输入形状
    if seq.ndim != 2:
        raise HTTPException(
            status_code=400,
            detail=f"sequence 必须是二维数组, 实际维度={seq.ndim}"
        )
    if seq.shape[1] != 5:
        raise HTTPException(
            status_code=400,
            detail=f"每个时间步需要 5 个特征, 实际={seq.shape[1]}"
        )
    if seq.shape[0] < 2:
        raise HTTPException(
            status_code=400,
            detail=f"序列长度至少为 2, 实际={seq.shape[0]}"
        )

    try:
        score = compute_anomaly_score(seq)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"推理失败: {str(e)}")

    is_abnormal = score > threshold

    return PredictResponse(
        deviceId=req.deviceId,
        score=round(score, 2),
        threshold=threshold,
        is_abnormal=is_abnormal,
        label="abnormal" if is_abnormal else "normal",
        schema_version=feature_schema,
    )


# ============================================================
# 直接运行入口
# ============================================================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
