# Threads::create_vm 源码导读

## 位置

`Threads::create_vm` 是 HotSpot 创建 VM 的核心入口。

源码位置：

- `src/hotspot/share/runtime/threads.cpp`

声明位置：

- `src/hotspot/share/runtime/threads.hpp`

调用入口：

- `src/hotspot/share/prims/jni.cpp`

核心调用链：

```text
JNI_CreateJavaVM
  -> JNI_CreateJavaVM_inner
  -> Threads::create_vm
```

`JNI_CreateJavaVM_inner` 负责处理“同一进程只能创建一个 VM”的全局状态，成功后返回 `JavaVM*` 和当前线程的 `JNIEnv*`。真正的大部分 VM 初始化工作在 `Threads::create_vm` 中完成。

## 总体职责

`Threads::create_vm` 不是只创建线程。它完成的是“把一个 native 进程变成可以运行 Java 代码的 HotSpot VM”。

大体包括：

1. 检查 JNI 版本。
2. 初始化 TLS、输出流、OS 层和基础内存池。
3. 处理 launcher 属性、系统属性和 VM 参数。
4. 初始化 NMT、ergonomics、flag range/constraint。
5. 初始化全局 runtime 数据结构。
6. 创建并挂接 main `JavaThread`。
7. 初始化 Universe、CodeCache、解释器、stub、类加载基础设施。
8. 创建 WatcherThread、VMThread、ServiceThread 等 VM 内部线程。
9. 初始化核心 Java 类、模块系统、system class loader。
10. 发出 JVMTI/JFR 生命周期事件。
11. 启动 attach listener、管理 agent、compiler、monitor deflation 等服务。
12. 标记 VM 初始化完成。

## 入口前：JNI_CreateJavaVM_inner 做了什么

在进入 `Threads::create_vm` 前，`JNI_CreateJavaVM_inner` 先做一层保护。

关键点：

- HotSpot 当前同一进程只支持一个 Java VM。
- 用原子变量 `vm_created` 防止多个线程同时创建 VM。
- 如果已有 VM 或正在创建，返回 `JNI_EEXIST`。
- 用 `safe_to_recreate_vm` 判断失败后是否允许重新尝试。
- 调用 `Threads::create_vm(args, &can_try_again)`。
- 成功后：
  - 设置 `*vm = &main_vm`
  - 设置 `*penv = current JavaThread 的 JNIEnv`
  - 把 `vm_created` 标记为 `COMPLETE`
  - 把当前线程状态从 `_thread_in_vm` 切回 `_thread_in_native`
- 失败后：
  - 清空 `*vm` 和 `*penv`
  - 根据 `can_try_again` 决定是否允许后续重试
  - 把 `vm_created` 重置为 `NOT_CREATED`

这说明 `Threads::create_vm` 的失败点很重要：早期失败可能允许重试，初始化深入之后失败通常不能安全重试。

## 阶段 1：早期 VM/OS 初始化

函数开头先做很早期、尽量不依赖 Java 世界的初始化。

主要步骤：

```text
VM_Version::early_initialize()
检查 JNI version
ThreadLocalStorage::init()
ostream_init()
Arguments::process_sun_java_launcher_properties(args)
os::init()
Arena::initialize_chunk_pool()
```

含义：

- `VM_Version::early_initialize()`：尽早准备 VM/CPU 版本相关信息。
- `is_supported_jni_version(args->version)`：拒绝不支持的 JNI Invocation API 版本。
- `ThreadLocalStorage::init()`：初始化 TLS，因为后续需要通过 TLS 找当前线程。
- `ostream_init()`：初始化 HotSpot 输出流系统，之后错误和日志才有地方输出。
- `Arguments::process_sun_java_launcher_properties(args)`：处理 launcher 传来的属性。
- `os::init()`：OS 层第一阶段初始化。
- `Arena::initialize_chunk_pool()`：初始化 Arena 内存池。

此时还没有 Java 堆、没有 `JavaThread`、没有 Universe。

## 阶段 2：系统属性、日志和参数解析

接下来开始建立 VM 参数环境。

主要步骤：

```text
Arguments::init_system_properties()
JDK_Version_init()
Arguments::init_version_specific_system_properties()
LogConfiguration::initialize(...)
Arguments::parse(args)
MemTracker::initialize()
os::init_before_ergo()
Arguments::apply_ergo()
JVMFlagLimit::check_all_ranges()
JVMFlagLimit::check_all_constraints(AfterErgo)
```

含义：

- 初始化系统属性，之后 `java.vm.*`、`os.*` 等属性有基础值。
- JDK 版本信息可用于后续参数解析和属性设置。
- 日志系统必须在参数解析前初始化，因为参数本身可能控制 logging。
- `Arguments::parse(args)` 解析 `-X`、`-XX`、system property、classpath/module 相关参数。
- NMT 在参数解析后马上初始化，目的是缩短 pre-NMT-init 窗口。
- ergonomics 会根据机器、GC、heap、compiler 等条件调整 flags。
- range/constraint 检查确保 ergonomics 后的 flag 值仍然合法。

如果这里失败，通常还处在比较早的阶段，风险比后面小。

## 阶段 3：OS 第二阶段和 JVMTI agent 加载

参数解析完成后，VM 可以进行依赖参数结果的 OS 初始化。

主要步骤：

```text
os::init_2()
SafepointMechanism::initialize()
Arguments::adjust_after_os()
ostream_init_log()
JvmtiAgentList::load_agents()
```

含义：

- `os::init_2()`：OS 层第二阶段初始化，依赖已经解析的 VM 参数。
- `SafepointMechanism::initialize()`：初始化 safepoint 机制。
- `Arguments::adjust_after_os()`：根据 OS 初始化后的结果再调整参数。
- `ostream_init_log()`：输出流与日志进一步接上。
- `load_agents()`：加载 `-agentlib`、`-agentpath`，以及转换后的 `-Xrun` agents。

这里还没有创建 main `JavaThread`，但 agent 的 onload 可能会注册后续需要的能力。

## 阶段 4：VM 全局结构和 main JavaThread

这是第一个关键拐点：VM 开始创建自己的线程模型。

主要步骤：

```text
_number_of_threads = 0
_number_of_non_daemon_threads = 0
vm_init_globals()
JavaThread::_thread_oop_storage = OopStorageSet::create_strong(...)
JavaThread* main_thread = new JavaThread()
main_thread->set_thread_state(_thread_in_vm)
main_thread->initialize_thread_current()
main_thread->record_stack_base_and_size()
main_thread->set_active_handles(JNIHandleBlock::allocate_block())
main_thread->set_monitor_owner_id(ThreadIdentifier::next())
Thread::set_as_starting_thread(main_thread)
create_stack_guard_pages()
ObjectMonitor::Initialize()
ObjectSynchronizer::initialize()
```

`vm_init_globals()` 定义在 `src/hotspot/share/runtime/init.cpp`，由 VM thread 语义上的早期全局初始化组成：

```text
check_ThreadShadow()
basic_types_init()
eventlog_init()
mutex_init()
universe_oopstorage_init()
perfMemory_init()
SuspendibleThreadSet_init()
ExternalsRecorder_init()
```

这一步非常关键：

- 创建 C++ 层面的 main `JavaThread`。
- 把当前 native/os 线程挂接成 HotSpot 认识的 JavaThread。
- 设置线程状态为 `_thread_in_vm`。
- 初始化 JNI handle block。
- 记录栈范围，注册到 NMT。
- 创建 stack guard pages。
- 初始化 Java-level synchronization 相关结构。

注意：这里的 `JavaThread` 还不是完全意义上 Java 层的 `java.lang.Thread` 对象。Java 层 Thread 对象要等后面初始化 `java.lang.Thread` 时创建并关联。

## 阶段 5：init_globals：基础 VM 子系统

`init_globals()` 负责第一批全局 VM 子系统初始化。它定义在 `src/hotspot/share/runtime/init.cpp`。

主要顺序：

```text
management_init()
JvmtiExport::initialize_oop_storage()
bytecodes_init()
classLoader_init1()
compilationPolicy_init()
codeCache_init()
VM_Version_init()
icache_init2()
initial_stubs_init()
SharedRuntime::generate_initial_stubs()
universe_init()
AOTCodeCache::init2()
AsyncLogWriter::initialize()
gc_barrier_stubs_init()
continuations_init()
continuation_stubs_init()
interpreter_init_stub()
accessFlags_init()
InterfaceSupport_init()
SharedRuntime::generate_stubs()
SharedRuntime::init_adapter_library()
```

可以把它理解成：

- 建立 bytecode、class loader、code cache、VM version、icache 的基础。
- 生成早期 runtime stubs。
- 初始化 `Universe`，也就是 VM 世界的核心对象和堆等基础结构。
- 初始化 GC barrier stubs、continuation stubs、interpreter stubs。
- 建立 shared runtime 和 adapter library。

`universe_init()` 是这里的重心之一。很多后续步骤都依赖 Universe 已经可用。

如果 `init_globals()` 失败，代码会删除 `main_thread`，把 `canTryAgain` 设为 `false`，并返回错误码。

## 阶段 6：加入线程列表和 init_globals2

`init_globals()` 后，VM 已经有足够基础，可以把 main thread 加入全局线程列表：

```text
WatcherThread::start()
Threads::add(main_thread)
init_globals2()
```

`Threads::add(main_thread)` 会：

- 把线程加入 `Threads` 列表。
- 执行 GC barrier 的 `on_thread_attach`。
- 增加线程计数。
- 加入 ThreadService。
- 加入 SMR thread list。
- 调整 ObjectMonitor 相关 ceiling。

为什么要在 `init_globals2()` 前加入？

源码注释说明：`init_globals2()` 会开始构建 Java 对象并触发 barriers，因此 main thread 必须先完成 attach/barrier 设置。

`init_globals2()` 主要做：

```text
universe2_init()
javaClasses_init()
interpreter_init_code()
referenceProcessor_init()
jni_handles_init()
vmStructs_init()
vtableStubs_init()
compilerOracle_init()
dependencyContext_init()
dependencies_init()
compileBroker_init()
JVMCI::initialize_globals()
TrainingData::initialize()
universe_post_init()
compiler_stubs_init()
final_stubs_init()
MethodHandles::generate_adapters()
```

可以把它理解成第二阶段 VM 世界成型：

- 加载 primordial classes。
- 初始化 Java class mirror 和 VM 内建 Java 类元数据。
- 生成解释器代码。
- 初始化 JNI handles。
- 初始化 vtable stubs、compiler oracle、compile broker。
- 做 `universe_post_init()`。
- 生成最终 stubs 和 MethodHandle adapters。

如果这里失败，处理比 `init_globals()` 更复杂：因为 Universe 可能已经初始化完成，也可能已有 pending exception，所以不能总是直接删除 main thread。

## 阶段 7：启动 VMThread

`init_globals2()` 成功后，会创建 VMThread：

```text
VMThread::create()
os::create_thread(vmthread, os::vm_thread)
os::start_thread(vmthread)
等待 vmthread->is_running()
```

VMThread 是 HotSpot 内部极重要的线程，用于执行 VM operations，例如 safepoint 下的操作、某些 GC/verification 操作等。

之后如果开启 `VerifyDuringStartup`，会通过：

```text
VMThread::execute(&verify_op)
```

执行启动期验证。

## 阶段 8：初始化核心 Java 类

接下来开始初始化 Java 层最核心的类。

主要入口：

```text
initialize_java_lang_classes(main_thread, CHECK_JNI_ERR)
```

它会初始化：

```text
java.lang.String
java.lang.System
java.lang.Class
java.lang.ThreadGroup
java.lang.Thread
java.lang.Module
jdk.internal.misc.UnsafeConstants
java.lang.reflect.Method
java.lang.ref.Finalizer
常见异常类，如 OutOfMemoryError、NullPointerException、StackOverflowError 等
```

其中还会：

- 创建初始 ThreadGroup。
- 创建并关联 main `java.lang.Thread` 对象。
- 调用 `System.initPhase1()`。
- 读取 Java runtime name/version/vendor 等信息。

这一步之后，main `JavaThread` 才真正和 Java 层线程对象建立联系。

随后：

```text
quicken_jni_functions()
StubCodeDesc::freeze()
set_init_completed()
LogConfiguration::post_initialize()
Metaspace::post_initialize()
MutexLockerImpl::post_initialize()
```

`set_init_completed()` 表示基础初始化已经完成。很多异常和 debug 逻辑要等基础类初始化完成后才可靠。

## 阶段 9：信号、Attach、服务线程和编译器

基础初始化完成后，开始启动运行期服务。

主要步骤：

```text
os::initialize_jdk_signal_support()
AttachListener::vm_start()
AttachListener::init()
JvmtiAgentList::load_xrun_agents()
Arena::start_chunk_pool_cleaner_task()
ServiceThread::initialize()
MonitorDeflationThread::initialize()
CompileBroker::compilation_init()
```

含义：

- 启动 JDK signal support。
- 根据参数启动 Attach Listener。
- 加载非 eager 的 `-Xrun` agents。
- 启动 Arena chunk pool cleaner。
- 启动 ServiceThread，用于 JVMTI deferred events 和清理任务。
- 启动 monitor deflation thread。
- 初始化 C1/C2/JVMCI 编译服务。

## 阶段 10：JSR292、模块系统和 System initPhase2/3

然后进入 Java 运行环境更完整的阶段。

主要步骤：

```text
initialize_jsr292_core_classes()
call_initPhase2()
JvmtiExport::enter_start_phase()
JvmtiExport::post_vm_start()
call_initPhase3()
SystemDictionary::compute_java_loaders()
```

`initialize_jsr292_core_classes()` 初始化 `java.lang.invoke` 的核心类：

```text
MethodHandle
ResolvedMethodName
MemberName
MethodHandleNatives
```

`call_initPhase2()` 调用 `System.initPhase2()`：

- 初始化模块系统。
- 在 phase 2 完成前，只能加载 `java.base` 类。
- phase 2 后，VM 才开始从 `-Xbootclasspath/a` 等位置搜索类。

`call_initPhase3()` 调用 `System.initPhase3()`：

- 完成 security manager、system class loader、线程上下文 class loader 等最终设置。

`SystemDictionary::compute_java_loaders()` 会缓存 system/platform class loader。

## 阶段 11：进入 live phase，完成 VM initialized 事件

后续步骤把 VM 从 start phase 推到 live phase。

主要步骤：

```text
JvmtiExport::enter_live_phase()
PerfMemory::set_accessible(true)
JvmtiExport::post_vm_initialized()
Management::initialize()
PerfDataManager::create_misc_perfdata()
JniPeriodicChecker::engage()
call_postVMInitHook()
WatcherThread::run_all_tasks()
```

含义：

- JVMTI 进入 live phase。
- perf memory 对外可访问。
- 发出 VM initialized 事件。
- 初始化 management agent。
- 创建 perfdata。
- 如果开启 `CheckJNICalls`，启动 JNI 周期检查。
- 调用 Java 层 `jdk.internal.vm.PostVMInitHook.run()`。
- 让 WatcherThread 跑一轮已注册的周期任务。

最后：

```text
create_vm_timer.end()
_vm_complete = true   // ASSERT build
return JNI_OK
```

至此，`Threads::create_vm` 完成。

## 失败处理要点

`Threads::create_vm` 有多个失败点。理解失败处理对读 startup bug 很重要。

常见返回：

- `JNI_EVERSION`：JNI version 不支持。
- `JNI_EINVAL`：参数、flag constraint、compiler oracle 等非法。
- `JNI_ENOMEM`：关键内部分配失败。
- `JNI_ERR`：通用初始化失败。
- `JNI_OK`：成功。

`canTryAgain` 的意义：

- 早期失败理论上可能允许再次调用 `JNI_CreateJavaVM`。
- 一旦初始化深入到部分全局结构已经建立，再重试可能导致崩溃。
- 所以很多失败路径会设置 `*canTryAgain = false`。

`JNI_CreateJavaVM_inner` 会根据这个标记决定是否恢复 `safe_to_recreate_vm`。

## 一张简化时序图

```text
JNI_CreateJavaVM_inner
  |
  |-- 检查是否已有 VM / 是否允许创建
  |
  v
Threads::create_vm
  |
  |-- 早期 TLS / ostream / os::init
  |-- 系统属性 / 参数解析 / NMT / ergonomics
  |-- os::init_2 / safepoint mechanism / JVMTI agents
  |-- vm_init_globals
  |-- 创建并挂接 main JavaThread
  |-- init_globals
  |-- WatcherThread::start
  |-- Threads::add(main_thread)
  |-- init_globals2
  |-- VMThread::create/start
  |-- initialize_java_lang_classes
  |-- set_init_completed
  |-- signal / attach / service / monitor / compiler
  |-- initialize_jsr292_core_classes
  |-- System.initPhase2
  |-- JVMTI VMStart
  |-- System.initPhase3
  |-- JVMTI live phase / VMInit
  |-- PostVMInitHook
  |
  v
JNI_OK
```

## 读源码时的抓手

建议按这几个断点读：

1. `Arguments::parse(args)`：参数和 flags 从这里真正进入 VM。
2. `vm_init_globals()`：最早一批全局 runtime 结构。
3. `new JavaThread()`：当前 native 线程变成 HotSpot main JavaThread。
4. `init_globals()`：Universe、CodeCache、初始 stubs、解释器基础。
5. `Threads::add(main_thread)`：main thread 进入全局线程列表。
6. `init_globals2()`：Java classes、interpreter code、JNI handles、compiler broker。
7. `VMThread::create()`：VMThread 启动。
8. `initialize_java_lang_classes()`：核心 Java 类和 main Java Thread 对象建立。
9. `call_initPhase2()`：模块系统初始化。
10. `call_initPhase3()`：system class loader 等最终 Java 层设置。

## 对应测试入口

`Threads::create_vm` 通常通过启动 JVM 间接测试。

推荐从这些测试开始：

- `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java`
- `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/exeGetCreatedJavaVMs.c`
- `test/hotspot/jtreg/runtime/NMT/NMTInitializationTest.java`

运行示例：

```bash
make CONF=macosx-aarch64-server-slowdebug test TEST="test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java"
make CONF=macosx-aarch64-server-slowdebug test TEST="test/hotspot/jtreg/runtime/NMT/NMTInitializationTest.java"
```

