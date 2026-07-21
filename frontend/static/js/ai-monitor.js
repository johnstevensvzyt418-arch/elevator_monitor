(function (window, document) {
    'use strict';

    var MAX_POINTS = 60;

    function AiMonitor(options) {
        this.options = options || {};
        this.targetDeviceId = this.options.targetDeviceId || '';
        this.currentDeviceId = this.targetDeviceId;
        this.records = {};
        this.histories = {};
        this.historyLoaded = {};
        this.historyLoading = {};
        this.lastStates = {};
        this.hoverIndex = -1;
        this.pointPositions = [];
        this.root = document.getElementById(this.options.rootId || 'aiPanel');
        if (!this.root) return;

        this.root.innerHTML = [
            '<div class="ai-panel-head">',
                '<div class="ai-heading"><span class="ai-chip">AI</span><div><strong>异常检测</strong><small>时序模型实时判断</small></div></div>',
                '<span class="ai-state ai-state-waiting" data-ai="state">等待数据</span>',
            '</div>',
            '<div class="ai-panel-content">',
                '<div class="ai-metrics">',
                    '<div class="ai-metric ai-metric-primary"><span>异常得分</span><strong data-ai="score">--</strong></div>',
                    '<div class="ai-metric"><span>判定阈值</span><strong data-ai="threshold">--</strong></div>',
                    '<div class="ai-metric"><span>距离阈值</span><strong data-ai="distance">--</strong></div>',
                    '<div class="ai-metric"><span>更新时间</span><strong class="ai-time-value" data-ai="time">--</strong></div>',
                '</div>',
                '<div class="ai-trend-head">',
                    '<div><strong>异常得分趋势</strong><span class="ai-history-note">最近 60 次推理</span></div>',
                    '<span data-ai="device">未选择设备</span>',
                '</div>',
                '<div class="ai-chart-legend" aria-hidden="true">',
                    '<span><i class="ai-legend-line ai-legend-score"></i>异常得分</span>',
                    '<span><i class="ai-legend-line ai-legend-average"></i>5点均线</span>',
                    '<span><i class="ai-legend-line ai-legend-threshold"></i>阈值</span>',
                '</div>',
                '<div class="ai-chart-wrap">',
                    '<canvas class="ai-trend" data-ai="trend" height="220" aria-label="AI 异常得分曲线，包含阈值和风险区域"></canvas>',
                    '<div class="ai-chart-tooltip" data-ai="tooltip" hidden></div>',
                '</div>',
                '<div class="ai-summary-grid">',
                    '<div class="ai-summary-item"><span>本次峰值</span><strong data-ai="peak">--</strong></div>',
                    '<div class="ai-summary-item"><span>超阈次数</span><strong data-ai="overCount">0</strong></div>',
                    '<div class="ai-summary-item"><span>窗口进度</span><strong data-ai="windowProgress">0/10</strong></div>',
                    '<div class="ai-summary-item"><span>AI服务</span><strong data-ai="service">等待</strong></div>',
                '</div>',
                '<div class="ai-panel-foot">',
                    '<span class="ai-foot-dot"></span>',
                    '<span data-ai="detail">等待设备上报</span>',
                '</div>',
            '</div>'
        ].join('');

        this.nodes = {};
        var names = ['state', 'score', 'threshold', 'distance', 'peak', 'overCount', 'windowProgress', 'service', 'time', 'device', 'trend', 'tooltip', 'detail'];
        for (var i = 0; i < names.length; i++) {
            this.nodes[names[i]] = this.root.querySelector('[data-ai="' + names[i] + '"]');
        }

        var self = this;
        window.addEventListener('resize', function () { self.drawTrend(); });
        this.nodes.trend.addEventListener('mousemove', function (event) { self.onPointerMove(event); });
        this.nodes.trend.addEventListener('mouseleave', function () { self.clearHover(); });

        this.renderEmpty();
        if (this.currentDeviceId) this.loadHistory(this.currentDeviceId);
    }

    AiMonitor.prototype.handle = function (data) {
        if (!this.root || !data) return;
        var deviceId = String(data.deviceId || data.Device || '');
        if (!deviceId) return;
        if (this.targetDeviceId && this.targetDeviceId !== deviceId) return;

        var normalized = this.normalize(data, deviceId);
        this.records[deviceId] = normalized;
        this.appendHistory(normalized);

        var previousState = this.lastStates[deviceId];
        this.lastStates[deviceId] = normalized.state;
        if (normalized.state === 'ABNORMAL' && previousState !== 'ABNORMAL' && typeof this.options.onAbnormal === 'function') {
            this.options.onAbnormal(normalized);
        }

        if (!this.currentDeviceId || this.currentDeviceId === deviceId || this.targetDeviceId) {
            this.currentDeviceId = this.targetDeviceId || deviceId;
            this.render(normalized);
        }
    };

    AiMonitor.prototype.showDevice = function (deviceId) {
        if (!this.root || this.targetDeviceId) return;
        this.currentDeviceId = String(deviceId || '');
        this.hoverIndex = -1;
        this.hideTooltip();
        if (this.records[this.currentDeviceId]) this.render(this.records[this.currentDeviceId]);
        else this.renderEmpty(this.currentDeviceId);
        this.loadHistory(this.currentDeviceId);
    };

    AiMonitor.prototype.loadHistory = function (deviceId) {
        var self = this;
        if (!deviceId || this.historyLoaded[deviceId] || this.historyLoading[deviceId]) return;
        this.historyLoading[deviceId] = true;

        fetch('/api/ai/history/' + encodeURIComponent(deviceId) + '?limit=' + MAX_POINTS, {
            cache: 'no-store',
            headers: { 'Accept': 'application/json' }
        })
        .then(function (response) {
            if (!response.ok) throw new Error('history request failed');
            return response.json();
        })
        .then(function (payload) {
            var merged = (self.histories[deviceId] || []).slice();
            var items = Array.isArray(payload.items) ? payload.items : [];
            for (var i = 0; i < items.length; i++) {
                var record = self.normalize(items[i], deviceId);
                if (record.ready && isFinite(record.score) && isFinite(record.threshold)) {
                    merged.push(toPoint(record));
                }
            }
            self.histories[deviceId] = deduplicateAndSort(merged).slice(-MAX_POINTS);
            self.historyLoaded[deviceId] = true;

            if (payload.latest) {
                var latest = self.normalize(payload.latest, deviceId);
                self.records[deviceId] = latest;
                if (latest.ready && isFinite(latest.score) && isFinite(latest.threshold)) {
                    self.histories[deviceId] = deduplicateAndSort(
                        (self.histories[deviceId] || []).concat([toPoint(latest)])
                    ).slice(-MAX_POINTS);
                }
            } else if (!self.records[deviceId] && self.histories[deviceId].length) {
                var lastPoint = self.histories[deviceId][self.histories[deviceId].length - 1];
                self.records[deviceId] = {
                    deviceId: deviceId,
                    state: lastPoint.score > lastPoint.threshold ? 'ABNORMAL' : 'NORMAL',
                    ready: true,
                    score: lastPoint.score,
                    threshold: lastPoint.threshold,
                    sampleCount: 10,
                    requiredSamples: 10,
                    updatedAt: lastPoint.updatedAt
                };
            }

            if (self.currentDeviceId === deviceId) {
                if (self.records[deviceId]) self.render(self.records[deviceId]);
                else self.renderEmpty(deviceId);
            }
        })
        .catch(function () {
            self.historyLoaded[deviceId] = true;
            if (self.currentDeviceId === deviceId) self.drawTrend();
        })
        .then(function () {
            delete self.historyLoading[deviceId];
        });
    };

    AiMonitor.prototype.appendHistory = function (record) {
        if (!record.ready || !isFinite(record.score) || !isFinite(record.threshold)) return;
        var deviceId = record.deviceId;
        var history = this.histories[deviceId] || [];
        history.push(toPoint(record));
        this.histories[deviceId] = deduplicateAndSort(history).slice(-MAX_POINTS);
    };

    AiMonitor.prototype.normalize = function (data, deviceId) {
        var score = Number(data.score);
        var threshold = Number(data.threshold);
        var state = String(data.state || '').toUpperCase();
        var abnormal = data.isAbnormal === true || data.is_abnormal === true || state === 'ABNORMAL';
        var ready = data.ready === true || (isFinite(score) && isFinite(threshold));
        if (!state) state = ready ? (abnormal ? 'ABNORMAL' : 'NORMAL') : 'COLLECTING';
        return {
            deviceId: deviceId,
            state: state,
            ready: ready,
            score: score,
            threshold: threshold,
            sampleCount: Number(data.sampleCount || 0),
            requiredSamples: Number(data.requiredSamples || 10),
            updatedAt: data.updatedAt || data.time || new Date().toISOString()
        };
    };

    AiMonitor.prototype.render = function (record) {
        var labels = {
            COLLECTING: '采集中',
            NORMAL: '运行正常',
            ABNORMAL: '检测异常',
            UNAVAILABLE: '服务不可用'
        };
        var state = labels[record.state] ? record.state : 'UNAVAILABLE';
        var stats = this.getStats(record.deviceId);

        this.root.className = 'ai-panel ai-panel-' + state.toLowerCase();
        this.nodes.state.className = 'ai-state ai-state-' + state.toLowerCase();
        this.nodes.state.textContent = labels[state];
        this.nodes.device.textContent = record.deviceId;
        this.nodes.time.textContent = formatTime(record.updatedAt);
        this.nodes.peak.textContent = stats.count ? stats.peak.toFixed(2) : '--';
        this.nodes.overCount.textContent = String(stats.overCount);
        this.nodes.windowProgress.textContent = Math.min(record.sampleCount || 0, record.requiredSamples || 10) + '/' + (record.requiredSamples || 10);
        this.nodes.service.textContent = state === 'UNAVAILABLE' ? '异常' : '正常';
        this.nodes.service.className = state === 'UNAVAILABLE' ? 'ai-negative' : 'ai-positive';

        if (record.ready && isFinite(record.score) && isFinite(record.threshold)) {
            this.nodes.score.textContent = record.score.toFixed(2);
            this.nodes.threshold.textContent = record.threshold.toFixed(2);
            this.nodes.distance.textContent = Math.abs(record.threshold - record.score).toFixed(2);
            this.nodes.detail.textContent = record.score > record.threshold
                ? '得分已超过阈值，请检查运行状态'
                : '最近得分处于正常范围';
        } else {
            this.nodes.score.textContent = '--';
            this.nodes.threshold.textContent = '--';
            this.nodes.distance.textContent = '--';
            this.nodes.detail.textContent = state === 'UNAVAILABLE'
                ? 'AI 服务暂时无响应'
                : '正在采集 ' + record.sampleCount + ' / ' + record.requiredSamples + ' 个样本';
        }
        this.drawTrend();
        if (typeof this.options.onUpdate === 'function') this.options.onUpdate(record);
    };

    AiMonitor.prototype.renderEmpty = function (deviceId) {
        if (!this.root) return;
        var id = deviceId || this.targetDeviceId || '';
        var stats = this.getStats(id);
        this.root.className = 'ai-panel ai-panel-waiting';
        this.nodes.state.className = 'ai-state ai-state-waiting';
        this.nodes.state.textContent = '等待数据';
        this.nodes.score.textContent = '--';
        this.nodes.threshold.textContent = '--';
        this.nodes.distance.textContent = '--';
        this.nodes.peak.textContent = stats.count ? stats.peak.toFixed(2) : '--';
        this.nodes.overCount.textContent = String(stats.overCount);
        this.nodes.windowProgress.textContent = '0/10';
        this.nodes.service.textContent = '等待';
        this.nodes.service.className = '';
        this.nodes.time.textContent = '--';
        this.nodes.device.textContent = id || '未选择设备';
        this.nodes.detail.textContent = id ? '等待该设备上报' : '等待设备上报';
        this.drawTrend();
        if (typeof this.options.onUpdate === 'function') {
            this.options.onUpdate({
                deviceId: id,
                state: 'WAITING',
                ready: false,
                sampleCount: 0,
                requiredSamples: 10
            });
        }
    };

    AiMonitor.prototype.getStats = function (deviceId) {
        var history = this.histories[deviceId] || [];
        var peak = 0;
        var overCount = 0;
        for (var i = 0; i < history.length; i++) {
            peak = Math.max(peak, history[i].score);
            if (history[i].score > history[i].threshold) overCount++;
        }
        return { count: history.length, peak: peak, overCount: overCount };
    };

    AiMonitor.prototype.drawTrend = function () {
        if (!this.root || !this.nodes.trend) return;
        var canvas = this.nodes.trend;
        var width = canvas.clientWidth || 640;
        var height = canvas.clientHeight || 220;
        var ratio = window.devicePixelRatio || 1;
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
        var ctx = canvas.getContext('2d');
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        ctx.clearRect(0, 0, width, height);

        var history = (this.histories[this.currentDeviceId] || []).filter(function (point) {
            return isFinite(point.score) && isFinite(point.threshold);
        });
        var left = 43;
        var right = 18;
        var top = 10;
        var bottom = 28;
        var plotWidth = Math.max(1, width - left - right);
        var plotHeight = Math.max(1, height - top - bottom);

        if (!history.length) {
            drawEmptyChart(ctx, left, top, plotWidth, plotHeight);
            this.pointPositions = [];
            return;
        }

        var threshold = history[history.length - 1].threshold;
        var maxScore = threshold;
        for (var i = 0; i < history.length; i++) maxScore = Math.max(maxScore, history[i].score);
        var yMax = Math.max(120, threshold * 1.34, maxScore * 1.08);
        var step = yMax <= 150 ? 30 : Math.max(50, Math.ceil((yMax / 4) / 50) * 50);
        yMax = Math.ceil(yMax / step) * step;
        var caution = threshold * 2 / 3;

        function yFor(value) {
            return top + plotHeight - clamp(value / yMax, 0, 1) * plotHeight;
        }

        var thresholdY = yFor(threshold);
        var cautionY = yFor(caution);
        ctx.fillStyle = 'rgba(201, 70, 61, 0.075)';
        ctx.fillRect(left, top, plotWidth, thresholdY - top);
        ctx.fillStyle = 'rgba(200, 129, 18, 0.075)';
        ctx.fillRect(left, thresholdY, plotWidth, cautionY - thresholdY);
        ctx.fillStyle = 'rgba(46, 125, 134, 0.055)';
        ctx.fillRect(left, cautionY, plotWidth, top + plotHeight - cautionY);

        ctx.font = '10px Bahnschrift, Microsoft YaHei, sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        for (var tick = 0; tick <= yMax; tick += step) {
            var tickY = yFor(tick);
            ctx.strokeStyle = '#DCE4E8';
            ctx.lineWidth = 1;
            ctx.setLineDash([3, 3]);
            ctx.beginPath();
            ctx.moveTo(left, tickY);
            ctx.lineTo(left + plotWidth, tickY);
            ctx.stroke();
            ctx.fillStyle = '#647480';
            ctx.fillText(String(tick), left - 8, tickY);
        }
        ctx.setLineDash([]);

        var timestamps = history.map(function (point, index) {
            var parsed = Date.parse(point.updatedAt);
            return isFinite(parsed) ? parsed : index;
        });
        var minTime = timestamps[0];
        var maxTime = timestamps[timestamps.length - 1];
        var useTimeScale = maxTime > minTime;
        var positions = [];
        for (var p = 0; p < history.length; p++) {
            var fraction = useTimeScale
                ? (timestamps[p] - minTime) / (maxTime - minTime)
                : (history.length === 1 ? 1 : p / (history.length - 1));
            positions.push({
                x: left + fraction * plotWidth,
                y: yFor(history[p].score),
                point: history[p]
            });
        }
        this.pointPositions = positions;

        var labelIndices = uniqueIndices([0, Math.round((history.length - 1) / 3), Math.round((history.length - 1) * 2 / 3), history.length - 1]);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        ctx.fillStyle = '#647480';
        for (var li = 0; li < labelIndices.length; li++) {
            var labelIndex = labelIndices[li];
            var labelX = positions[labelIndex].x;
            if (labelIndex === 0) ctx.textAlign = 'left';
            else if (labelIndex === history.length - 1) ctx.textAlign = 'right';
            else ctx.textAlign = 'center';
            ctx.fillText(formatAxisTime(history[labelIndex].updatedAt), labelX, top + plotHeight + 8);
        }

        ctx.strokeStyle = '#C88112';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([6, 5]);
        ctx.beginPath();
        ctx.moveTo(left, thresholdY);
        ctx.lineTo(left + plotWidth, thresholdY);
        ctx.stroke();
        ctx.setLineDash([]);
        var thresholdLabel = '阈值 ' + threshold.toFixed(0);
        ctx.font = '10px Microsoft YaHei, sans-serif';
        var labelWidth = ctx.measureText(thresholdLabel).width + 10;
        ctx.fillStyle = 'rgba(255,255,255,0.88)';
        ctx.fillRect(left + plotWidth - labelWidth, thresholdY - 9, labelWidth, 17);
        ctx.fillStyle = '#A96608';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.fillText(thresholdLabel, left + plotWidth - 3, thresholdY);

        var areaGradient = ctx.createLinearGradient(0, top, 0, top + plotHeight);
        areaGradient.addColorStop(0, 'rgba(46, 125, 134, 0.22)');
        areaGradient.addColorStop(1, 'rgba(46, 125, 134, 0.01)');
        ctx.fillStyle = areaGradient;
        ctx.beginPath();
        ctx.moveTo(positions[0].x, top + plotHeight);
        for (var a = 0; a < positions.length; a++) ctx.lineTo(positions[a].x, positions[a].y);
        ctx.lineTo(positions[positions.length - 1].x, top + plotHeight);
        ctx.closePath();
        ctx.fill();

        if (history.length >= 3) {
            ctx.strokeStyle = 'rgba(106, 139, 148, 0.65)';
            ctx.lineWidth = 1.2;
            ctx.beginPath();
            for (var avgIndex = 0; avgIndex < history.length; avgIndex++) {
                var avgStart = Math.max(0, avgIndex - 4);
                var total = 0;
                for (var avgPoint = avgStart; avgPoint <= avgIndex; avgPoint++) total += history[avgPoint].score;
                var average = total / (avgIndex - avgStart + 1);
                var avgY = yFor(average);
                if (avgIndex === 0) ctx.moveTo(positions[avgIndex].x, avgY);
                else ctx.lineTo(positions[avgIndex].x, avgY);
            }
            ctx.stroke();
        }

        ctx.lineWidth = 2.3;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        if (positions.length === 1) {
            drawPoint(ctx, positions[0].x, positions[0].y, history[0].score > threshold ? '#C9463D' : '#2E7D86', 3.5);
        } else {
            for (var segment = 1; segment < positions.length; segment++) {
                var isAlarm = history[segment - 1].score > threshold || history[segment].score > threshold;
                ctx.strokeStyle = isAlarm ? '#C9463D' : '#2E7D86';
                ctx.beginPath();
                ctx.moveTo(positions[segment - 1].x, positions[segment - 1].y);
                ctx.lineTo(positions[segment].x, positions[segment].y);
                ctx.stroke();
                if (history[segment].score > threshold && history[segment - 1].score <= threshold) {
                    drawPoint(ctx, positions[segment].x, positions[segment].y, '#C9463D', 4.2);
                }
            }
        }

        var last = positions[positions.length - 1];
        drawPoint(ctx, last.x, last.y, history[history.length - 1].score > threshold ? '#C9463D' : '#2E7D86', 3.2);
        if (this.hoverIndex >= 0 && positions[this.hoverIndex]) {
            var hover = positions[this.hoverIndex];
            drawPoint(ctx, hover.x, hover.y, hover.point.score > hover.point.threshold ? '#C9463D' : '#2E7D86', 5);
        }
    };

    AiMonitor.prototype.onPointerMove = function (event) {
        if (!this.pointPositions.length) return;
        var rect = this.nodes.trend.getBoundingClientRect();
        var pointerX = event.clientX - rect.left;
        var nearest = 0;
        var distance = Infinity;
        for (var i = 0; i < this.pointPositions.length; i++) {
            var currentDistance = Math.abs(this.pointPositions[i].x - pointerX);
            if (currentDistance < distance) {
                distance = currentDistance;
                nearest = i;
            }
        }
        this.hoverIndex = nearest;
        var position = this.pointPositions[nearest];
        var point = position.point;
        this.nodes.tooltip.innerHTML =
            '<span>' + escapeHtml(formatTime(point.updatedAt)) + '</span>' +
            '<strong>得分 ' + point.score.toFixed(2) + '</strong>' +
            '<small>阈值 ' + point.threshold.toFixed(2) + '</small>';
        this.nodes.tooltip.hidden = false;
        var tooltipWidth = 154;
        var tooltipHeight = 42;
        this.nodes.tooltip.style.left = clamp(position.x + 10, 4, rect.width - tooltipWidth - 4) + 'px';
        this.nodes.tooltip.style.top = clamp(position.y - tooltipHeight - 8, 4, rect.height - tooltipHeight - 4) + 'px';
        this.drawTrend();
    };

    AiMonitor.prototype.clearHover = function () {
        this.hoverIndex = -1;
        this.hideTooltip();
        this.drawTrend();
    };

    AiMonitor.prototype.hideTooltip = function () {
        if (this.nodes.tooltip) this.nodes.tooltip.hidden = true;
    };

    function toPoint(record) {
        return {
            score: Number(record.score),
            threshold: Number(record.threshold),
            updatedAt: record.updatedAt || new Date().toISOString()
        };
    }

    function deduplicateAndSort(history) {
        var seen = {};
        var result = [];
        history.sort(function (a, b) {
            return timeValue(a.updatedAt) - timeValue(b.updatedAt);
        });
        for (var i = 0; i < history.length; i++) {
            var point = history[i];
            var key = String(point.updatedAt) + '|' + point.score.toFixed(4) + '|' + point.threshold.toFixed(4);
            if (!seen[key]) {
                seen[key] = true;
                result.push(point);
            }
        }
        return result;
    }

    function drawEmptyChart(ctx, left, top, width, height) {
        ctx.fillStyle = 'rgba(46, 125, 134, 0.035)';
        ctx.fillRect(left, top, width, height);
        ctx.strokeStyle = '#DCE4E8';
        ctx.setLineDash([3, 4]);
        for (var i = 0; i <= 3; i++) {
            var y = top + height * i / 3;
            ctx.beginPath();
            ctx.moveTo(left, y);
            ctx.lineTo(left + width, y);
            ctx.stroke();
        }
        ctx.setLineDash([]);
        ctx.fillStyle = '#91A4AD';
        ctx.font = '11px Microsoft YaHei, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('收集到推理结果后显示曲线', left + width / 2, top + height / 2);
    }

    function drawPoint(ctx, x, y, color, radius) {
        ctx.fillStyle = '#FFFFFF';
        ctx.beginPath();
        ctx.arc(x, y, radius + 2, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        ctx.fill();
    }

    function uniqueIndices(values) {
        var seen = {};
        return values.filter(function (value) {
            if (seen[value]) return false;
            seen[value] = true;
            return true;
        });
    }

    function timeValue(value) {
        var parsed = Date.parse(value);
        return isFinite(parsed) ? parsed : 0;
    }

    function formatTime(value) {
        var date = new Date(value);
        if (isNaN(date.getTime())) return String(value || '--');
        return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
    }

    function formatAxisTime(value) {
        var date = new Date(value);
        if (isNaN(date.getTime())) return '--:--';
        return pad(date.getHours()) + ':' + pad(date.getMinutes());
    }

    function pad(value) {
        return value < 10 ? '0' + value : String(value);
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, function (character) {
            return {
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;'
            }[character];
        });
    }

    window.AiMonitor = {
        init: function (options) { return new AiMonitor(options); }
    };
})(window, document);
