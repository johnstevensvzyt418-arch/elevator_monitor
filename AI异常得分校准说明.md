# AI 异常得分校准说明

## 问题原因

此前在线 AI 服务加载的是新时达（xinshida）数据训练出的 LSTM-AE 模型，但现场设备上报的是 MNK 协议特征：

1. 门状态
2. 当前楼层
3. 目标楼层
4. 运行方向
5. 速度

两套数据的字段含义和数值分布不一致。正常 MNK 数据送入旧模型后，重构误差会被放大，因此电梯正常运行时也会长期超过阈值 90。这不是把阈值简单调高就能可靠解决的问题。

## 本次处理方法

- 保留界面判定阈值 **90**，没有通过抬高阈值掩盖误报。
- 新增 MNK 专用特征版本 `mnk-v2`，后端和 AI 服务会校验版本，禁止旧、新特征混用。
- 使用 97 条已确认正常的现场历史记录建立协议专用统计基线。
- 对 5 个状态特征及其一阶变化量进行稳健协方差评分。
- 使用正则化协方差，避免旧评分矩阵病态导致某些维度被异常放大。
- 使用窗口 P95 聚合，降低单点通信抖动造成的误报。
- 将现场正常数据的原始边界校准到显示阈值 90，前端含义保持一致。
- Redis 时序键升级为 `ai:series:mnk-v2:{deviceId}`，不会混入旧窗口。
- AI 推理使用单线程有序队列，避免多线程事件重排破坏时间序列。
- 超速、开门运行、楼层跳变和方向不一致等明确安全条件仍由规则引擎独立判断。

## 主要文件

- `ai_service/protocol_baseline.py`：MNK 时序特征和评分实现。
- `ai_service/calibrate_protocol.py`：从正常历史数据生成基线文件。
- `ai_service/trained_models/mnk/protocol-baseline-v1.npz`：本次生成的校准参数。
- `ai_service/config.toml`：启用 `protocol_baseline`、`mnk-v2` 和阈值 90。
- `ai_service/infer_server.py`：加载协议基线、校验特征版本并返回校准后得分。
- `backend/.../AiAlarmListener.java`：统一现场特征映射并发布版本化 AI 结果。
- `backend/.../AiPredictClient.java`：请求中携带 `schemaVersion=mnk-v2`。
- `backend/.../TimeSeriesBuffer.java`：使用新的版本化 Redis 时序键。
- `backend/.../AsyncConfig.java`：新增有序的 `aiExecutor`。

## 阈值说明

前端阈值仍为 **90**。内部基线根据正常历史计算原始边界，再归一化为前端分数：

- 得分小于或等于 90：模型判定正常。
- 得分大于 90：模型判定异常。
- 规则引擎触发：即使统计得分未超过 90，仍按明确安全规则告警。

重新采集了更多正常工况后，应重新执行校准脚本，而不是手工修改 90：

```bash
python ai_service/calibrate_protocol.py \
  --input normal-history.tsv \
  --output ai_service/trained_models/mnk/protocol-baseline-v1.npz
```

输入数据至少需要 50 条，并且只能使用已经确认无故障、无告警的记录。

## 2026-07-18 验证结果

- 正常历史离线验证：P50 约 37.08、P95/最大值约 61.11，低于 90。
- 模拟超速：约 837.48，高于 90。
- 模拟连续楼层跳变：约 187.36，高于 90。
- 服务器 AI 健康检查：`scoring_backend=protocol_baseline`、`schema_version=mnk-v2`、校准样本 97 条。
- 服务器内部正常序列接口测试：22.72 / 90，判定正常。
- 服务器内部模拟超速接口测试：837.48 / 90，判定异常。
- 完整链路回放测试：上报 10 条正常 MNK 报文后，后端按顺序形成 10 条窗口，AI 返回 13.80 / 90，并通过 Redis 发布 `NORMAL`。
- 测试设备产生的 Redis 和 MySQL 数据已清理。

## 服务地址

- 监控前端：<http://119.91.74.53:8080/monitor>
- AI 健康检查：<http://119.91.74.53:8000/health>（公网端口可能受安全组或代理限制，容器内调用正常）
- AI 推理接口：`POST http://119.91.74.53:8000/predict`
- 后端 MNK 上报：`POST http://119.91.74.53:10008/api/v2/mnk`

## 当前真实设备状态

真实设备 `0024002b` 在部署验证时处于离线状态。旧模型生成的错误异常缓存已经清除，当前 AI 状态为 `COLLECTING 0/10`。设备恢复上报后，系统会收集 10 条 `mnk-v2` 样本并自动给出新的正常或异常结果。

## 回滚位置

服务器部署前文件备份位于：

```text
/home/ubuntu/elevator_monitor/.codex-backup-ai-calibration-20260718-1946
```
