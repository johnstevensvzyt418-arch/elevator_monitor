-- ============================================================
-- Elevator Monitor — MySQL 历史数据表 DDL
-- 数据库: elevator_monitor
-- 引擎: InnoDB (事务 + 行锁)
-- ============================================================

CREATE DATABASE IF NOT EXISTS elevator_monitor
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE elevator_monitor;

-- 电梯运行历史记录表
CREATE TABLE IF NOT EXISTS elevator_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    device_id       VARCHAR(32)  NOT NULL COMMENT '电梯设备ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间',
    report_time     VARCHAR(32)  DEFAULT NULL COMMENT '设备上报时间字符串',
    current_floor   VARCHAR(8)   DEFAULT NULL COMMENT '当前楼层',
    target_floor    VARCHAR(8)   DEFAULT NULL COMMENT '目标楼层',
    direction       VARCHAR(4)   DEFAULT NULL COMMENT '运行方向: 00=平层 01=上行 02=下行',
    speed           DOUBLE       DEFAULT NULL COMMENT '当前速度 (m/s)',
    door_status     VARCHAR(4)   DEFAULT NULL COMMENT '门状态: 00=关门到位 01=开门到位 02=关门中 03=开门中',
    elevator_status VARCHAR(4)   DEFAULT NULL COMMENT '电梯状态: 00=正常',
    alarm           VARCHAR(32)  DEFAULT NULL COMMENT '报警状态',
    run_times       INT          DEFAULT NULL COMMENT '累计运行次数',
    distance        INT          DEFAULT NULL COMMENT '累计运行里程 (层站数)',
    runtime         VARCHAR(32)  DEFAULT NULL COMMENT '运行时间 (格式化)',
    passenger       VARCHAR(4)   DEFAULT NULL COMMENT '乘客: 00=无人 01=有人',

    -- 联合索引: 按设备+时间范围查询历史轨迹
    INDEX idx_device_time (device_id, created_at),
    -- 单列索引: 按时间范围全局查询
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电梯运行历史记录';

-- ============================================================
-- 告警事件表
-- ============================================================
CREATE TABLE IF NOT EXISTS alarm_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    device_id       VARCHAR(32)  NOT NULL COMMENT '电梯设备ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    event_type      VARCHAR(16)  NOT NULL COMMENT '事件类型: FIRED / CLEARED',
    rule_name       VARCHAR(64)  NOT NULL COMMENT '规则名称: LONG_IDLE / DOOR_OPEN_TOO_LONG / ...',
    alarm_level     VARCHAR(16)  NOT NULL COMMENT '告警级别: INFO / WARN / CRITICAL',
    title           VARCHAR(128) DEFAULT NULL COMMENT '告警标题',
    detail          VARCHAR(512) DEFAULT NULL COMMENT '告警详情',
    current_floor   VARCHAR(8)   DEFAULT NULL COMMENT '触发时楼层',
    speed           DOUBLE       DEFAULT NULL COMMENT '触发时速度',
    active          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否仍处于激活状态',

    INDEX idx_alarm_device_time (device_id, created_at),
    INDEX idx_alarm_rule         (rule_name),
    INDEX idx_alarm_active       (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件记录';
