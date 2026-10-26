# CampusLife 简历项目材料

## 项目名称

**校邻 CampusLife — 校园生活分享与周边发现平台**

**技术栈：** Java 17、Spring Boot、MyBatis-Plus、MySQL、Redis、Redisson、JWT、WebSocket、Vue 3、uni-app、高德地图。

## 简历版本：Java 后端方向

**项目描述：** 面向校园用户构建生活分享与本地商户发现平台，覆盖真实周边地点检索、站内店铺、图文笔记、社交互动、优惠券和运营管理，采用 Spring Boot 后端与 Vue / uni-app 前端分离架构。

**技术实践：**

- 封装高德 v5 周边搜索服务，统一参数校验、超时与错误处理，通过一次请求获取商户基础信息、评分及图片，完成外部 POI 与内部店铺的独立建模与业务接入。
- 基于 Redis Lua 实现 IP 请求计数与窗口过期，支持限流阈值及可信代理配置；通过服务端安全代理管理地图密钥，统一接口返回结构。
- 设计浏览器原生 WGS84 到高德 GCJ-02 的显式转换链路，配合请求版本号、条件快照、分页去重和失败保留，实现地图、定位和商户列表的一致更新。
- 在店铺、社交和营销业务中应用 Redis 缓存、GEO、Set、ZSet、Lua 与 Stream，结合 Redisson 锁、JWT 拦截器和 WebSocket 通知组织业务流程。
- 为新增周边模块编写 8 项后端测试与 11 项前端测试，覆盖上游响应、可选字段、限流、请求竞态和坐标转换。

简历篇幅有限时保留前三条；根据岗位要求选用缓存、秒杀或消息模块展开。

## 项目一页说明

| 维度 | 内容 |
| --- | --- |
| 用户场景 | 校园周边发现、探店分享与站内商户互动 |
| 系统组成 | Spring Boot API、uni-app 用户端、Vue 运营后台 |
| 数据组织 | MySQL 存储内部业务；Redis 处理缓存、地理检索、互动关系和消息流；高德提供外部 POI |
| 关键设计 | 内部 Shop 与外部 POI 分离；定位只转换一次；查询结果按快照与版本提交 |
| 接口保护 | 参数校验、Redis Lua 限流、超时、服务端密钥、固定代理路径 |
| 验证证据 | 8 项后端测试、11 项前端测试、独立 Redis 集成测试、H5 构建与交互验证脚本 |

## 两分钟演示顺序

1. **场景入口：** 从首页“周边好店”进入真实地点页。
2. **核心操作：** 定位、切换分类与半径、搜索关键词，展示商家图片、评分及距离。
3. **交互闭环：** 点击地图标记与卡片，展示选中同步、分页及地址复制。
4. **业务边界：** 切换“站内店铺”，展示内部详情、笔记与优惠券关联。
5. **工程说明：** 展示查询封装、坐标转换、限流脚本和状态测试的关键代码。

## 面试讲解入口

| 主题 | 源码 | 讲解要点 |
| --- | --- | --- |
| 外部接口集成 | `src/main/java/com/cn/nearby/AmapNearbyService.java` | 单次周边请求、扩展字段、参数校验与响应清洗 |
| 限流与代理 | `src/main/java/com/cn/nearby/NearbyRateLimiter.java` | Lua 原子计数、窗口过期、IP 来源与异常返回 |
| 请求一致性 | `uni-app-project/utils/nearby-state.js` | 查询快照、版本号、迟到响应、分页提交与去重 |
| 坐标链路 | `uni-app-project/utils/nearby-location.js` | 浏览器原生 WGS84、一次官方转换、共享查询中心 |
| 缓存设计 | `src/main/java/com/cn/utils/cache/CacheClient.java` | 空值缓存、互斥重建、逻辑过期策略 |
| 优惠券流程 | `src/main/java/com/cn/service/impl/VoucherOrderServiceImpl.java` | 库存与重复校验、Stream 消费、锁及订单状态 |
| 社交与消息 | `src/main/java/com/cn/service/impl/BlogServiceImpl.java`、`src/main/java/com/cn/service/impl/NotificationServiceImpl.java` | 点赞、Feed 与通知推送 |

涉及吞吐、延迟、命中率等指标时，以实际运行与测量结果填写；本文使用的是源码能力和可核对的测试数量。
