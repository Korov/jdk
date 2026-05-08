# HotSpot Runtime 学习路线

## 方向

主战场：

- `hotspot/runtime`

辅助战场：

- `test/jtreg`
- `core-libs`

目标不是一次学完整个 OpenJDK，而是在一个区域里变得可靠：理解 runtime 的实现，能写扎实的回归测试，并提交 reviewer 容易理解和信任的 patch。

## 长期目标

- 理解 JVM 启动、Java 线程、类加载、同步、JNI、异常和 VM operation。
- 能为 runtime 问题写出高质量 jtreg 回归测试。
- 能判断 runtime 修改对 `core-libs` 中 Java 层行为的影响。
- 能在 patch 中清楚解释：问题是什么、为什么这样修、测了什么、风险在哪里。

## 重点源码区域

Runtime 以及相关 HotSpot 代码：

- `src/hotspot/share/runtime/`
- `src/hotspot/share/classfile/`
- `src/hotspot/share/oops/`
- `src/hotspot/share/interpreter/`
- `src/hotspot/share/prims/`

测试和 Java 层行为：

- `test/hotspot/jtreg/runtime/`
- `test/jdk/java/lang/`
- `test/jdk/java/lang/invoke/`
- `src/java.base/share/classes/`

## 第一批主题

按这个顺序学习。每个主题的目标是画出主调用链，并至少关联一个测试。

1. `Threads::create_vm`：JVM 启动路径。
2. `JavaThread`：Java 线程的 runtime 表示和线程状态切换。
3. `SystemDictionary`：类解析和类加载入口。
4. `InstanceKlass`：类元数据和 runtime 表示。
5. `JavaCalls`：VM 调用 Java 方法的路径。
6. `Exceptions`：异常创建、传播，以及 VM 到 Java 的边界。
7. `jni.cpp`：JNI 调用边界和 handle 管理。
8. `ObjectSynchronizer`：monitor、同步和锁的基础。

## 第 1 个月：建立 Runtime 地图

先读结构，不要求第一次就完全读透。

需要回答的问题：

- JVM 启动有哪些主要阶段？
- 第一个 Java 线程是什么时候创建的？
- `Universe`、`SystemDictionary`、`ClassLoaderDataGraph` 分别负责什么？
- 启动失败时，错误如何返回给 launcher？
- 各个 runtime 子系统的测试分别放在哪里？

常用搜索：

```bash
rg "Threads::create_vm" src/hotspot
rg "init_globals" src/hotspot
rg "universe_init" src/hotspot
rg "SystemDictionary::initialize" src/hotspot
```

常用历史命令：

```bash
git log --oneline -- src/hotspot/share/runtime/thread.cpp
git log --oneline -- src/hotspot/share/classfile/systemDictionary.cpp
```

## 第 2 个月：通过测试反推设计

用测试反推设计和行为。

每周循环：

1. 从 `test/hotspot/jtreg/runtime/` 里选 2 到 3 个测试。
2. 判断测试覆盖的行为。
3. 找到对应的 C++ 实现。
4. 阅读相关 commit 历史或 JBS issue。
5. 做一个小的本地实验，故意破坏这个行为。
6. 运行测试，确认失败模式和自己的理解一致。

常用测试命令：

```bash
make CONF=macosx-aarch64-server-slowdebug test TEST="hotspot_runtime"
make CONF=macosx-aarch64-server-slowdebug test TEST="test/hotspot/jtreg/runtime/ClassFile"
make CONF=macosx-aarch64-server-slowdebug test TEST="test/jdk/java/lang/Class"
```

## 第 3 个月：开始稳定处理小问题

适合起步的问题类型：

- Runtime 测试间歇失败。
- 改进 crash、assert 或诊断信息。
- 小范围 jtreg 测试清理。
- 范围明确的平台相关 runtime 修复。
- 涉及 VM 边界的小型 `core-libs` 行为问题。

需要谨慎的方向：

- 大范围 safepoint 协议修改。
- 深层类加载并发语义修改。
- monitor 和锁语义修改。
- JNI 或 JVMTI 兼容性行为修改。
- 可能牵涉 C1、C2 或 GC 行为的修改。

## 每周节奏

- 2 天：学习一个 runtime 子主题。
- 1 天：阅读相关历史 commit、PR 或 review 讨论。
- 1 天：运行测试，并做一个小实验 patch。
- 1 天：分析一个 JBS issue，或写一篇学习笔记。

每周产出一个具体成果：

- 一篇调用链笔记。
- 一个最小复现。
- 一个 jtreg 测试草稿。
- 一条 JBS 分析评论。
- 一个小 PR。

## Patch 质量检查清单

打开或更新 PR 前检查：

- 行为变化是否足够聚焦？
- 是否有回归测试？
- 没有修复时，测试是否会失败？
- 是否运行了最小相关测试集？
- 是否说明了平台假设？
- PR 说明是否讲清楚了 bug、fix、tests 和 risk？
- 是否检查过被修改文件的近期历史？

## 第一个具体练习

从 `Threads::create_vm` 开始。

跟踪：

```text
launcher -> JNI_CreateJavaVM -> Threads::create_vm -> init_globals -> runtime initialization
```

在单独笔记中回答：

- Java 代码运行前，哪些全局系统已经完成初始化？
- 哪个阶段初始化 `Universe`？
- 哪个阶段初始化 `SystemDictionary`？
- main `JavaThread` 是什么时候 attach 的？
- 初始化失败时会发生什么？
