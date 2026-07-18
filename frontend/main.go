package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
)

type Cache struct {
	mutex   sync.RWMutex
	clients map[string]*websocket.Conn
}

type infopack struct {
	Device    string
	Status    string
	Floor     string
	ToFloor   string
	Direction string
	Door      string
	Passenger string
	Speed     string
	Alarm     string
	Runtime   string
	Distance  string
	Times     string
}

func (p *Cache) Init() {
	p.clients = make(map[string]*websocket.Conn, 10)
}

// 向已连接的服务页面推送消息
func (p *Cache) SendMessage(data string) (int, int) {
	p.mutex.RLock()
	defer p.mutex.RUnlock()

	textData := []byte(data)

	var succ int
	var fail int

	for _, conn := range p.clients {
		if conn != nil {
			if err := conn.WriteMessage(websocket.TextMessage, textData); err != nil {
				fail++
			} else {
				succ++
			}
		}
	}

	log.Printf("send message to ws client, succ: %v, fail: %v", succ, fail)

	return succ, fail
}

func (p *Cache) AddClient(conn *websocket.Conn) {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	id := fmt.Sprintf("client_%p", conn)
	p.clients[id] = conn

	log.Printf("add %v", id)
}

func (p *Cache) DeleteClient(conn *websocket.Conn) {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	id := fmt.Sprintf("client_%p", conn)
	delete(p.clients, id)
	log.Printf("delete %v", id)
}

// 以下是WebSocket类功能实现基础
var wsupgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// 解决跨域问题
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

func wshandler(w http.ResponseWriter, r *http.Request) {
	conn, err := wsupgrader.Upgrade(w, r, nil)
	if err != nil {
		fmt.Printf("Failed to set websocket upgrade: %+v\n", err)
		return
	}

	if cache != nil {
		cache.AddClient(conn)
	}

	for {
		t, msg, err := conn.ReadMessage()
		if err != nil {
			break
		}
		conn.WriteMessage(t, msg)
	}

	if cache != nil {
		cache.DeleteClient(conn)
	}
}

var cache *Cache
var redisQueryClient *redis.Client

// redisSubscriber 订阅 Redis elevator:status 频道，收到消息后通过 WebSocket 广播。
// 支持自动重连：连接断开后每 3 秒重试。
func redisSubscriber(redisAddr, redisPassword string) {
	ctx := context.Background()

	for {
		rdb := redis.NewClient(&redis.Options{
			Addr:     redisAddr,
			Password: redisPassword,
			DB:       0,
		})

		// 同时订阅设备状态、AI 推理结果和规则告警频道。
		pubsub := rdb.Subscribe(ctx, "elevator:status", "elevator:ai_result", "elevator:alarm")
		log.Printf("[Redis] 已连接到 %s，订阅频道 elevator:status + elevator:ai_result + elevator:alarm", redisAddr)

		// 阻塞读取消息
		ch := pubsub.Channel()
		for msg := range ch {
			log.Printf("[Redis] 收到 %s: %s", msg.Channel, msg.Payload)
			if msg.Channel == "elevator:alarm" {
				// 规则引擎告警（DEVICE_OFFLINE / LEVELING_TIMEOUT 等）
				// 包装为 ai_result 类型以复用前端的 handleRuleAlarm 处理
				alarmMsg := fmt.Sprintf(`{"type":"ai_result","data":%s}`, msg.Payload)
				if cache != nil {
					cache.SendMessage(alarmMsg)
				}
				continue
			}
			if msg.Channel == "elevator:ai_result" {
				// 只保存已完成推理的 mnk-v2 结果，供页面刷新后恢复最近趋势。
				var result struct {
					DeviceID      string `json:"deviceId"`
					SchemaVersion string `json:"schemaVersion"`
					Ready         bool   `json:"ready"`
				}
				if err := json.Unmarshal([]byte(msg.Payload), &result); err == nil &&
					result.DeviceID != "" && result.SchemaVersion == "mnk-v2" && result.Ready {
					historyKey := "ai:history:mnk-v2:" + result.DeviceID
					pipe := rdb.Pipeline()
					pipe.LPush(ctx, historyKey, msg.Payload)
					pipe.LTrim(ctx, historyKey, 0, 59)
					pipe.Expire(ctx, historyKey, 7*24*time.Hour)
					if _, err := pipe.Exec(ctx); err != nil {
						log.Printf("[Redis] AI 历史保存失败 deviceId=%s: %v", result.DeviceID, err)
					}
				}

				// AI 推理结果：包装为带类型的 JSON 后推送到 WebSocket。
				aiMsg := fmt.Sprintf(`{"type":"ai_result","data":%s}`, msg.Payload)
				if cache != nil {
					cache.SendMessage(aiMsg)
				}
				continue
			}
			if cache != nil {
				cache.SendMessage(msg.Payload)
			}
		}

		// 频道关闭（Redis 断连），清理并重试
		log.Printf("[Redis] 连接断开，3秒后重连...")
		pubsub.Close()
		rdb.Close()
		time.Sleep(3 * time.Second)
	}
}

// 程序入口
func main() {
	// 获取程序运行目录
	curDir, _ := filepath.Abs(filepath.Dir(os.Args[0]))

	var port = 8080
	var err error

	if len(os.Args) > 1 {
		if port, err = strconv.Atoi(os.Args[1]); err != nil {
			log.Printf("not found server port, use default port: 8080")
			port = 8080
		}
	}
	servPort := fmt.Sprintf(":%d", port)

	fmt.Printf("Program is running at %v\n", curDir)

	// 创建缓存，保存websocket连接信息
	cache = new(Cache)
	cache.Init()

	// -------------------------------------------------------
	// 启动 Redis 订阅协程（通过环境变量配置连接参数）
	// -------------------------------------------------------
	redisHost := os.Getenv("REDIS_HOST")
	if redisHost == "" {
		redisHost = "127.0.0.1"
	}
	redisPort := os.Getenv("REDIS_PORT")
	if redisPort == "" {
		redisPort = "6379"
	}
	redisPassword := os.Getenv("REDIS_PASSWORD")
	redisAddr := fmt.Sprintf("%s:%s", redisHost, redisPort)
	redisQueryClient = redis.NewClient(&redis.Options{
		Addr:     redisAddr,
		Password: redisPassword,
		DB:       0,
	})

	go redisSubscriber(redisAddr, redisPassword)
	log.Printf("[Redis] 订阅协程已启动，目标: %s", redisAddr)

	// 创建web server路由
	r := gin.Default()

	// 客户端测试页面，包含websocket客户端

	//r.LoadHTMLFiles("./indexTest.html")
	//r.LoadHTMLFiles("./show.html")
	r.LoadHTMLGlob("html/*") //加载网页模板
	//r.LoadHTMLFiles("./index2.html")

	//定义入口页面
	r.GET("/show", func(c *gin.Context) {
		c.HTML(200, "index2.html", nil)
	})

	// 无过滤监控页面（显示所有设备数据）
	r.GET("/monitor", func(c *gin.Context) {
		c.HTML(200, "index1.html", nil)
	})

	//定义搜索页面
	r.GET("/", func(c *gin.Context) {
		//c.HTML(200, "indexTest.html", nil)
		c.HTML(200, "search.html", nil)
	})

	//设置静态资源目录
	r.Static("/static", "./static")

	// AI 最近推理历史：同源接口避免跨域，页面刷新后可恢复最近 60 个得分点。
	r.GET("/api/ai/history/:deviceId", func(c *gin.Context) {
		deviceID := c.Param("deviceId")
		if deviceID == "" || len(deviceID) > 64 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "INVALID_DEVICE_ID"})
			return
		}

		limit := 60
		if value, err := strconv.Atoi(c.DefaultQuery("limit", "60")); err == nil {
			if value < 1 {
				value = 1
			}
			if value > 60 {
				value = 60
			}
			limit = value
		}

		if redisQueryClient == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "REDIS_UNAVAILABLE"})
			return
		}

		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		items, err := redisQueryClient.LRange(
			ctx, "ai:history:mnk-v2:"+deviceID, 0, int64(limit-1),
		).Result()
		if err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "HISTORY_UNAVAILABLE"})
			return
		}

		// Redis 中为新到旧，接口返回旧到新，前端可以直接绘图。
		history := make([]json.RawMessage, 0, len(items))
		for i := len(items) - 1; i >= 0; i-- {
			if json.Valid([]byte(items[i])) {
				history = append(history, json.RawMessage(items[i]))
			}
		}

		var latest json.RawMessage
		if raw, err := redisQueryClient.HGet(ctx, "elevator:ai_result", deviceID).Result(); err == nil && json.Valid([]byte(raw)) {
			latest = json.RawMessage(raw)
		}

		c.Header("Cache-Control", "no-store")
		c.JSON(http.StatusOK, gin.H{
			"deviceId": deviceID,
			"items":    history,
			"latest":   latest,
		})
	})

	// api请求处理 (⚠️ 调试端点，仅用于开发测试。生产环境应通过后端 POST → Redis 推送)
	r.GET("/api", func(c *gin.Context) {
		dt := fmt.Sprintf("%s, ", time.Now().Format("2006-01-02 15:04:05"))

		// 接收到推送的消息
		device := c.Query("device")
		status := c.Query("status")
		floor := c.Query("floor")
		toFloor := c.Query("toFloor")
		direction := c.Query("direction")
		door := c.Query("door")
		passenger := c.Query("passenger")
		speed := c.Query("speed")
		alarm := c.Query("alarm")
		runtime := c.Query("runtime")
		distance := c.Query("distance")
		times := c.Query("times")

		if device == "" || status == "" || floor == "" || direction == "" || door == "" || passenger == "" || speed == "" {
			c.String(200, dt+"数据不够")
			return
		}

		//将结构体封装成json格式，并返回[]byte
		inf := infopack{device, status, floor, toFloor, direction, door, passenger, speed, alarm, runtime, distance, times}
		log.Printf("[HTTP] %s", inf)
		msgbyte, err := json.Marshal(inf)

		if err != nil {
			c.String(200, dt+"信息解码失败 %v", err)
			return
		}

		msg := string(msgbyte)

		if cache != nil {
			succ, fail := cache.SendMessage(msg)
			c.String(200, dt+"上报信息成功，通知内容：%s, 已通知客户端, 成功： %d，失败：%d", msg, succ, fail)
			return
		}

		c.String(200, dt+"上报信息失败，没有cache")
	})

	// 健康检查 / Redis 状态检查
	r.GET("/health", func(c *gin.Context) {
		cnt := 0
		if cache != nil {
			cache.mutex.RLock()
			cnt = len(cache.clients)
			cache.mutex.RUnlock()
		}
		c.JSON(200, gin.H{
			"status":        "ok",
			"ws_clients":    cnt,
			"redis_channel": "elevator:status",
			"time":          time.Now().Format("2006-01-02 15:04:05"),
		})
	})
	// websocket连接请求处理
	r.GET("/ws", func(c *gin.Context) {
		// 处理客户端websocket长连接
		wshandler(c.Writer, c.Request)
	})
	// 修改-----处理websocket页面
	r.GET("/ws2", func(c *gin.Context) {
		// 处理客户端websocket长连接
		wshandler(c.Writer, c.Request)
	})

	// 循环监听
	r.Run(servPort)
}
