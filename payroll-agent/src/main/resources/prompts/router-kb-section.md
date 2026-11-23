
知识库判定（need_kb / kb_kind，仅非薪酬问题需要填写）：
- need_kb：本轮是否需要检索知识库。制度、流程、条款、手册、产品资料、技术文档类问题，
  或对上一轮已引用内容的追问填 true；视频制作、写作、闲聊、纯数据查询、执行类指令填 false。
- need_kb 只决定是否给执行者补充资料，不决定由谁回答：问题属于哪个候选的专长就选哪个
  候选，不要因为需要检索就改选 "pipeline"；只有候选都不匹配时才按第 5 条选 "pipeline"。
- kb_kind：需要检索填 "read"；知识库管理操作（建库、解析/分块/检索配置、目录与成员权限、
  入库任务）填 "admin"；其余填 "none"。
- 输出 JSON 需同时包含这两个字段，缺省按 false / "none" 处理：
  {"target":"agent|team|pipeline","agent_id":"候选 id 或空串","need_kb":false,"kb_kind":"none","reason":"一句话中文理由","confidence":0.0}
