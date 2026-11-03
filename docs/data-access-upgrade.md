# 数据访问规范化与性能验证改造

本轮仅修改读取链路；ParticipationService、Booking、CheckInService、Events、Publisher 和 Worker 事务代码未改动。业务状态机、锁顺序、Lua、request_id、唯一约束以及 Outbox 同事务提交均保留。

改造的实际效果、三轮并发结果与仍存在的瓶颈见[实验效果与结论](experiment-results.md)。Docker按本轮要求跳过。

## 数据访问清单

| 场景 | 实现 | 说明 |
|---|---|---|
| 场馆 CRUD | 原 VenueMapper | 已合理使用 MP，继续保留 |
| 已发布活动详情 | ActivityMapper + CatalogReadService | MP LambdaQueryWrapper，未发布/不存在均404 |
| 管理端场次列表 | SessionMapper + CatalogReadService | 显式实体映射及 Lambda 条件，保留后端场馆权限与ID排序 |
| 管理端评价列表 | ReviewMapper + CatalogReadService | Lambda 条件与倒序、固定上限100 |
| 活动列表与总数 | CatalogQueryMapper.xml | 共享where、动态条件、场馆权限EXISTS、关联场次、分页 |
| 用户参与历史 | XML JOIN + resultMap DTO | 资格/场次/活动/场馆关联，先分页后关联，保持ID倒序和20条分页 |
| 统计 | 原 Operations.factsBatch | 已有四条批量GROUP BY/条件聚合，不重复改造 |
| 核心写入 | 原 JdbcTemplate | 保留锁、条件更新、影响行数检查、事务和upsert |

没有强行增加 IService 继承层、分页插件或通用实体基类。旧列表接口返回数组，总数为独立接口，因此 XML 直接绑定 LIMIT/OFFSET，避免 Page 自动count改变查询次数。活动与场次一对多不直接展开分页，使用关联子查询/EXISTS，避免同一个活动重复或总数失真。

## 查询与映射约定

原公开/管理员活动列表与count新增可选 from/to（YYYY-MM-DD），按已发布场次 start_at 匹配，from含、to不含。未指定时保持原行为；from>=to、非法日期、负场馆ID、越界页码返回400。普通用户始终只查询 PUBLISHED，管理员状态筛选和场馆授权由 Java 生成受控 Filter，不允许前端注入授权范围。

参与历史原有字段保留，新增 activity_id、venue_id、venue_name。resultMap 显式映射空候补序号、截止时间和本地时间字段；实体使用 SnakeCase JSON 命名保持响应字段约定。MyBatis call-setters-on-nulls=true 保留 Map 中的空字段，与 JdbcTemplate 查询一致。所有参数使用 #{}，不使用字符串拼接SQL参数。

场次/评价/活动读取的实体不承接预约写入。活动发布/取消及编辑虽然有部分CRUD形式，但涉及状态机和锁，不迁移为无条件 updateById。评价upsert涉及签到资格验证，也保留原事务SQL。

## 索引与深分页

已有 ix_activity_venue(venue_id,status,id)、ix_activity_category(category,status,id)、ix_session_activity(activity_id,status,start_at)、ix_user_history(user_id,id) 能服务现有过滤。没有证据支持新增索引；20261103-query-indexes.sql 只在旧库缺失时补齐既有索引，不删除已有索引，已验证重复执行。现有数据不需要重建。

在30000条历史样本上比较先JOIN再分页与延迟关联，返回行完全相同，后者减少关联行数，因此采用先选20条再JOIN。保留原offset分页和10000页边界，不改游标协议。深分页仍需要扫描偏移前的索引记录，不能宣称已消除深分页成本。计划、原SQL及测量数据见 evidence/orm-query-plans.json。

## 验证

```powershell
mvn -s settings.xml test '-Dculturehub.localTest=true' -DskipTests=false
# 仅复测XML、映射与分页（仍继承核心预约回归）
mvn -s settings.xml -pl culturehub-business -am test '-Dculturehub.localTest=true' '-Dtest=CatalogLocalTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

localTest 只允许专用13306/culturehub测试库，会清空测试业务表，不能指向演示库。新增测试覆盖动态筛选、count/list一致、权限、日期、SQL参数输入、空值、关联不放大分页、MP实体读取；继承原并发、候补、签到、幂等和回滚测试。

API Mockito测试在当前受限环境无法附加Java agent，明确使用 subclass MockMaker，保持原测试断言，不跳过测试。

实际结果：四模块构建成功，131次测试执行通过、74次Docker测试跳过、0失败/错误；包含继承用例重复执行。XML改为延迟关联后又运行了27次 CatalogLocalTest（含继承回归），并完成最终打包。Vue交互测试通过；esbuild在沙箱内被EPERM阻止后于沙箱外构建成功。详细suite计数见 evidence/data-access-tests.json。

真实HTTP压测、测试环境、限流拆分、故障注入与局限详见 performance-report.md；复现说明见 ../scripts/perf/README.md。本轮不需要真实AI调用，也未重测完整Kafka投递或Docker部署。
