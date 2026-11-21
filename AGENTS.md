# Tune — agent instructions

## PERMANENT RULE: GitHub cloud only

**Anything and everything — coding, building, tests, compilations, runs,
verification — happens on GitHub cloud. No hardware use. Ever.**

- Never run gradle, npm/pnpm, adb, emulators, compilers, linters, or tests
  on a local machine for this project. Not once.
- Coordinate via `gh`: repos, Contents API, Actions logs, releases.
- Do not keep local working copies of this repo for building. Read-only
  reference clones of upstream sources (tune, Bitchord) are for reading
  only — never build or run them either.
- All code lands via the GitHub API or `git push` (transport only), then
  GitHub Actions builds, tests, and releases it.

## Project facts

- Standalone local Android music player (Kotlin Multiplatform:
  `androidApp` + `sharedLogic`). No iOS, no desktop, no sync.
- Playback core (FFmpeg + AAudio JNI) is proven — do not replace it.
- Package `com.exodidio.tune`, GPLv3, attribution in NOTICE.
- Keep CI green. Stack changes in small commits. Tag test releases `v*`.
- Branding gate: CI fails on `tune|exodidio` outside NOTICE/attribution.
- Icon source: `brand/icon-source.png` (uploaded by owner) → `icon.yml`
  workflow generates the transparent icon set.


## 阻塞操作与并发编程强制规范

本规范适用于所有代码的新增、修改、重构和代码审查。必须严格遵守，不得为了简化实现而忽略。

核心目标：避免阻塞操作导致 UI 卡死、事件循环停滞、线程池耗尽、线程饥饿、死锁以及程序无响应。

### 一、核心原则

1. 任何执行时间不可预测、可能等待外部资源或其他线程的操作，都必须视为潜在阻塞操作。
2. 阻塞操作只能在明确允许阻塞的工作线程中执行。
3. UI 线程、事件循环线程、主线程及其他对实时响应有要求的线程，禁止执行阻塞操作。
4. 优先使用真正的异步 API，而不是简单地将同步 API 包装成异步任务。
5. 不允许通过无限增加线程数量、延长超时时间或忽略异常来掩盖阻塞问题。
6. 必须从完整调用链分析线程执行语义，不能只检查当前方法。
7. 不确定某个 API 是否阻塞时，默认按照可能阻塞处理，并查阅其 API 文档或实现。

### 二、阻塞操作识别

以下操作均属于潜在阻塞操作，必须特别注意：

1. 网络操作

- HTTP/HTTPS 请求。
- Socket 连接、读写和等待数据。
- DNS 域名解析。
- 网络文件下载和上传。
- WebSocket 同步读写。
- 等待网络请求完成。
- 等待远程服务响应。
- 通过网络访问数据库、RPC 或其他远程服务。

2. 磁盘与文件操作

- 文件读取、写入、复制、移动和删除。
- 目录遍历和文件搜索。
- 大文件解析、解压缩和序列化。
- 文件锁获取。
- 文件同步和刷盘操作。
- 可能触发磁盘 I/O 的缓存访问。
- 大量小文件的批量操作。

注意：文件 API 即使在语法上是普通函数，也不代表执行过程不会阻塞。

3. 数据库操作

- 数据库查询、插入、更新和删除。
- 数据库事务提交。
- 数据库连接池获取连接。
- 数据库锁等待。
- 数据库迁移和初始化。
- 可能触发磁盘 I/O 的缓存未命中操作。

4. 线程与进程等待

以下操作必须视为阻塞：

- Thread.sleep()
- Thread.join()
- Future.get()
- CompletableFuture.join()
- CountDownLatch.await()
- Semaphore.acquire()
- BlockingQueue.take()
- Lock.lock()
- Condition.await()
- Process.waitFor()
- 等待子进程、线程或任务完成。
- 任何可能无限等待的同步原语。

5. 其他潜在阻塞操作

- IPC、Binder、管道等进程间通信。
- 调用外部命令或 Shell。
- 同步访问系统服务。
- 等待用户输入或设备响应。
- 可能触发阻塞的日志写入。
- 大量 CPU 运算导致线程长时间无法响应。
- 第三方库中未明确保证非阻塞的操作。

### 三、线程使用规范

1. UI 线程

严格禁止：

- 同步网络请求。
- 文件读写。
- 耗时数据库查询。
- 大规模文件遍历。
- 大量 CPU 密集型计算。
- Thread.sleep()。
- Future.get()、join() 等同步等待。
- 等待其他线程完成任务。
- 任何可能长时间持有锁的操作。

UI 线程只负责轻量级 UI 更新、事件处理和快速任务调度。

2. 事件循环线程

适用于 Node.js、JavaScript、Python asyncio 等事件驱动环境。

严格禁止：

- 同步网络请求。
- 同步文件 I/O。
- 同步数据库访问。
- Thread.sleep() 或等效阻塞操作。
- 长时间 CPU 密集型运算。
- 同步等待异步任务完成。
- 任何会阻塞事件循环的第三方 API。

需要执行阻塞操作时，必须交给适当的工作线程或独立进程执行。

3. I/O 工作线程

允许执行阻塞 I/O，但必须满足：

- 使用合理的线程池或执行器。
- 设置合理的超时时间。
- 避免无限等待。
- 正确处理异常和取消。
- 避免长时间占用有限的线程池资源。
- 避免在持有锁期间执行 I/O。
- 不得在同一个有限线程池内形成循环等待。

4. CPU 工作线程

CPU 密集型任务应与 I/O 密集型任务隔离。

例如：

- 图像处理。
- 大规模数据计算。
- 压缩和解压缩。
- 加密和解密。
- 复杂的数据解析。
- 大量 JSON/XML 处理。

根据运行环境选择线程池或进程池，避免 CPU 密集型任务挤占 I/O 任务的执行资源。

### 四、异步 API 使用规范

1. 优先使用原生异步 API。
2. 禁止将同步阻塞 API 误认为异步 API。
3. 禁止在异步任务启动后立即调用 get()、join() 等方法同步等待结果。
4. 异步任务之间优先使用 Future、Promise、CompletableFuture、Coroutine、async/await 等机制组合。
5. 异步回调中禁止直接执行耗时的阻塞操作。
6. 异步任务必须有明确的成功、失败和取消处理逻辑。
7. 异步任务必须正确处理超时。
8. 不得吞掉异步任务异常。
9. 必须避免回调嵌套过深，优先使用结构化并发或清晰的任务组合方式。
10. 必须注意异步任务的生命周期，避免任务完成后访问已经销毁的对象。
11. 不得为了维持同步代码风格而使用阻塞等待来获取异步结果。
12. 如果第三方 API 只有同步接口，应将其放入明确的阻塞工作线程中。

特别注意：

以下代码并不是真正的非阻塞操作：

- 在 CompletableFuture 中执行同步网络请求。
- 在 async 函数中直接调用同步文件 API。
- 在 JavaScript Promise 中执行同步阻塞操作。
- 在 Python async 函数中直接执行同步网络请求。
- 在协程中使用阻塞式 sleep。
- 使用 runBlocking 等机制阻塞调用线程。

### 五、超时、取消与资源管理

所有可能等待外部资源的操作都应设置合理的超时。

根据 API 能力，分别设置：

- 连接超时。
- 读取超时。
- 写入超时。
- 整体请求超时。
- 获取连接或锁的等待超时。
- 子进程执行超时。

具体要求：

1. 不允许使用无限超时的网络请求。
2. 不允许无限等待外部进程。
3. 对可能长期等待的锁，优先考虑 tryLock 和超时获取。
4. 超时发生后必须正确清理资源。
5. 取消任务时，应尽可能中断或终止底层操作，而不只是取消外层 Future。
6. 必须正确关闭文件、Socket、数据库连接和其他资源。
7. 使用 finally、try-with-resources、上下文管理器或等效机制确保资源释放。
8. 注意超时不一定意味着底层操作立即停止，必须确认底层 API 的取消语义。

### 六、锁与死锁预防

1. 尽量减少锁的使用范围。
2. 禁止在 synchronized 或其他锁的持有期间执行网络 I/O。
3. 禁止在持有锁期间执行磁盘 I/O。
4. 禁止在持有锁期间等待其他线程或异步任务。
5. 禁止在锁内调用可能执行用户代码或触发未知回调的外部方法。
6. 多把锁必须保持一致的获取顺序。
7. 避免嵌套锁。
8. 优先使用原子变量、并发容器、不可变对象和消息传递机制。
9. 不得在锁内调用可能反向获取其他锁的方法。
10. 对于锁竞争严重的场景，应考虑无锁设计或降低共享状态。

### 七、线程池与任务调度

1. I/O 密集型任务和 CPU 密集型任务应使用不同的执行器。
2. 不允许让大量长期阻塞任务占满一个有限线程池。
3. 不允许任务在同一个已被占满的线程池中提交子任务，然后同步等待子任务完成。
4. 对任务队列设置合理的容量或背压机制。
5. 对并发请求设置合理的上限。
6. 避免无限制地创建线程。
7. 对长时间运行的任务，应考虑独立的执行器。
8. 对任务执行时间、队列长度和失败率进行必要的监控。
9. 根据实际负载选择线程池大小，不得盲目扩大线程池。
10. 任务拒绝、队列满和线程池关闭时必须有明确的处理逻辑。

### 八、跨语言实现要求

必须根据具体语言选择正确的异步机制，不得混淆不同语言的执行模型。

Java / Android

- 优先使用 OkHttp enqueue()、HttpClient.sendAsync() 等真正的异步网络 API。
- 同步网络 API 只能在专用 I/O 工作线程中使用。
- Android 主线程禁止网络和耗时磁盘操作。
- Kotlin 协程中使用适当的 Dispatchers.IO 执行阻塞 I/O。
- CPU 密集型任务使用 Dispatchers.Default。
- 禁止在主线程调用 runBlocking。
- 禁止在持锁期间执行网络请求。
- 避免在 Android 生命周期结束后继续更新 UI。

Python

- asyncio 环境中优先使用 aiohttp、httpx.AsyncClient 等异步接口。
- 同步阻塞操作使用 asyncio.to_thread()、线程池或进程池。
- CPU 密集型任务根据情况使用进程池。
- 禁止在事件循环中直接调用 requests 等同步网络接口。
- 禁止在 async 函数中直接执行 time.sleep()，应使用 asyncio.sleep()。
- 注意 asyncio.Lock 与 threading.Lock 的使用场景不同。

JavaScript / Node.js

- 优先使用 fetch、异步文件 API 和非阻塞数据库接口。
- 避免使用同步文件 API，例如 fs.readFileSync()。
- 避免在事件循环中执行长时间 CPU 密集型任务。
- CPU 密集型任务应考虑 Worker Threads 或独立进程。
- 不要在 Promise 或 async 函数中直接执行同步阻塞 API。
- 注意同步 JSON 解析、正则表达式和大型数据处理也可能阻塞事件循环。

### 九、代码审查与修改要求

每次新增或修改代码时，必须主动检查完整调用链中的阻塞风险。

必须逐项检查：

1. 是否存在同步网络请求？
2. 是否存在同步磁盘 I/O？
3. 是否存在同步数据库操作？
4. 是否存在可能阻塞的 IPC 或外部进程调用？
5. 是否存在 Future.get()、join()、await() 等同步等待？
6. 是否存在 Thread.sleep() 或等效操作？
7. 是否在 UI 线程或事件循环线程中执行阻塞操作？
8. 是否在持锁期间执行 I/O 或等待其他任务？
9. 是否存在锁顺序不一致或循环等待？
10. 是否可能导致线程池耗尽？
11. 是否存在没有超时机制的外部调用？
12. 是否正确处理任务取消和资源释放？
13. 是否存在异步任务异常被吞掉的情况？
14. 是否存在 CPU 密集型任务阻塞事件循环？
15. 是否存在同步包装异步或异步包装同步导致的假异步问题？

如果发现问题，必须优先修复，不允许仅通过增加线程池大小、增加 sleep 或添加注释来规避。

### 十、特殊情况与例外

某些阻塞操作在特定场景下是合理的，例如：

- 程序启动阶段的同步初始化。
- 专门设计的命令行工具。
- 明确运行在独立工作线程中的同步业务流程。
- 必须同步执行的底层 API。
- 测试代码中的同步等待。

但必须同时满足：

1. 调用线程明确允许阻塞。
2. 不影响 UI、事件循环或其他关键线程。
3. 不会导致线程池资源耗尽。
4. 等待时间有合理上限，或者有明确的生命周期约束。
5. 阻塞行为对调用方清晰可见。

例外必须有明确理由，不得默认所有同步代码都是安全的。

### 十一、Agent 执行要求

在编写代码前：

- 识别当前运行环境和线程模型。
- 确认所使用 API 的同步或异步语义。
- 确认调用方所在的线程。
- 规划阻塞操作的执行位置。

在编写代码时：

- 严格遵守上述线程与异步规则。
- 不得为了减少代码行数而使用不安全的同步等待。
- 不得隐藏阻塞行为。
- 保证异常、超时、取消和资源释放逻辑完整。

在完成代码后：

- 主动检查修改部分及其上下游调用链。
- 搜索高风险同步 API。
- 检查线程池是否可能饥饿或耗尽。
- 检查锁与异步任务之间是否存在循环等待。
- 对发现的问题进行修复。

最终回复中，简要说明：

1. 本次涉及哪些阻塞操作。
2. 它们运行在哪些线程或执行器中。
3. 使用了什么异步机制。
4. 设置了哪些超时和取消机制。
5. 是否仍存在已知的阻塞风险。

**最高优先级原则：任何阻塞操作都必须经过明确的线程安全性判断。不得仅因为某个方法可以正常编译、正常运行或被包装在异步函数中，就认为它不会阻塞。**

## 未认可方案不得作为依赖

完整条款见 `.qoder/rules/unapproved-solutions.md`：Agent 自行引入且用户未认可的方案，不得作为后续开发或其他模块的依赖基础；用户要求“照原本/内置/参考实现来”时，必须弃用自建方案并在认可基准上重建。

## 已验证无效的旧路径不得作为兜底

完整条款见 `.qoder/rules/verified-path-first.md`：用户未认可、且实测已证明无效的实现，不得因为“一开始就是这么写的”而保留为默认或兜底；实测有效的方法必须作为主路径，无效路径直接删除。
