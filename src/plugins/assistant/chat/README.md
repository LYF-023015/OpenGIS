# 聊天功能阅读顺序

主入口只有一个：`ChatView.tsx`。它负责组合界面，不承载协议和持久化细节。

1. `ChatView.tsx`：先看这里，理解聊天页面整体流程。
2. `model/chatStore.ts`：会话状态和用户动作；流式消息归并在 `chatMessageReducer.ts`。
3. `composer`：输入框、附件选择和文件浏览。
4. `messages`：普通消息、Markdown、图片和消息分组。
5. `activity`：工具调用、代码执行、计划和子 Agent 等运行过程。
6. `chrome`：标题栏、搜索、会话选择和欢迎区。
7. `data/chatPersistence.ts`：聊天记录的落盘适配。

每个子目录最多只表达上面一种界面概念，不再放进笼统的 `components` 目录。
