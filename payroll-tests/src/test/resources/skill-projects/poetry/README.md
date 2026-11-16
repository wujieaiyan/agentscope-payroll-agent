# poetry（测试夹具）

目录式技能项目，用于验证导入、初始化与运行期数据挂载。

- `setup.sh` 会把诗词数据下载到 `data/`；
- `scripts/query.py` 从 `/data` 读取并检索诗词；
- `data/` 不随包发布。
