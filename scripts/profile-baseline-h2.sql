-- 工作偏好 P0-0 基线（H2 文件库版，本机默认库：./data/payroll）
-- 用法（先停掉 Web / runtime，H2 文件库为单写者）：
--   java -cp <h2.jar> org.h2.tools.RunScript \
--     -url "jdbc:h2:file:./data/payroll;ACCESS_MODE_DATA=r" -user sa -password "" \
--     -script scripts/profile-baseline-h2.sql -showResults
-- 或把 data/payroll.mv.db 复制到临时目录后对副本执行，避免锁库。
-- 只读：仅 SELECT，不改表结构、不写数据。
-- 词表来源 scripts/profile-baseline-keywords.txt；正则与 MySQL 版保持一致，改词表时同步修改。

-- 1. 每会话平均用户轮数
SELECT '每会话平均用户轮数' AS metric,
       ROUND(AVG(user_turns), 2) AS metric_value,
       COUNT(*) AS sessions
FROM (SELECT session_id, COUNT(*) AS user_turns
      FROM chat_messages
      WHERE role = 'USER'
      GROUP BY session_id) s;

-- 2. 首问未含范围词比例：每个会话 message_id 最小的用户消息
WITH first_user AS (
    SELECT cm.session_id AS session_id, cm.content AS content
    FROM chat_messages cm
    JOIN (SELECT session_id, MIN(message_id) AS min_id
          FROM chat_messages
          WHERE role = 'USER'
          GROUP BY session_id) f
      ON f.min_id = cm.message_id
)
SELECT '首问未含范围词比例' AS metric,
       COUNT(*) AS sessions,
       SUM(CASE WHEN content REGEXP
           '研发部|销售部|财务部|生产部|人力资源部|北京|上海|深圳|杭州|华东区?|华南区?|华北区?|本月|上月|本季度|上季度|本年|去年|今年|这个月|上个月|下个月'
           THEN 0 ELSE 1 END) AS misses,
       ROUND(SUM(CASE WHEN content REGEXP
           '研发部|销售部|财务部|生产部|人力资源部|北京|上海|深圳|杭州|华东区?|华南区?|华北区?|本月|上月|本季度|上季度|本年|去年|今年|这个月|上个月|下个月'
           THEN 0 ELSE 1 END) * 1.0 / COUNT(*), 4) AS ratio
FROM first_user;

-- 3. 需要补充口径的会话比例：会话内至少两轮用户消息且出现修正词
WITH per_session AS (
    SELECT session_id AS session_id,
           COUNT(*) AS user_turns,
           SUM(CASE WHEN content REGEXP
               '不是|不对|我说的是|改成|应该是|按.{0,6}(口径|算|发|部门|城市)|不要按|别按|重算'
               THEN 1 ELSE 0 END) AS corrections
    FROM chat_messages
    WHERE role = 'USER'
    GROUP BY session_id
)
SELECT '需要补充口径的会话比例' AS metric,
       COUNT(*) AS sessions,
       SUM(CASE WHEN user_turns >= 2 AND corrections > 0 THEN 1 ELSE 0 END) AS corrected,
       ROUND(SUM(CASE WHEN user_turns >= 2 AND corrections > 0 THEN 1 ELSE 0 END) * 1.0 / COUNT(*), 4) AS ratio
FROM per_session;

-- 4. 单轮成功率
SELECT '单轮成功率' AS metric,
       COUNT(*) AS turns,
       SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,
       ROUND(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) * 1.0 / COUNT(*), 4) AS ratio
FROM chat_turns;

-- 5. 团队路径占比：动态团队成功运行数 / 轮次成功数
SELECT '团队路径占比' AS metric,
       (SELECT COUNT(*) FROM agent_team_runs WHERE status = 'SUCCESS') AS team_success,
       (SELECT COUNT(*) FROM chat_turns WHERE status = 'SUCCEEDED') AS turn_success,
       ROUND(
           (SELECT COUNT(*) FROM agent_team_runs WHERE status = 'SUCCESS') * 1.0
           / NULLIF((SELECT COUNT(*) FROM chat_turns WHERE status = 'SUCCEEDED'), 0),
           4) AS ratio;

-- 附：轮次状态分布，便于核对样本量
SELECT status, COUNT(*) AS turns
FROM chat_turns
GROUP BY status
ORDER BY turns DESC;
