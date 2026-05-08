# Threads::create_vm 测试入口笔记

## 结论

`Threads::create_vm` 没有普通意义上的直接单元测试。它是 VM 启动核心入口，通常通过启动 JVM 或 native 程序调用 `JNI_CreateJavaVM` 来间接覆盖。

核心调用链：

```text
java launcher / native launcher
  -> JNI_CreateJavaVM
  -> JNI_CreateJavaVM_inner
  -> Threads::create_vm
```

相关源码：

- `src/hotspot/share/prims/jni.cpp`
- `src/hotspot/share/runtime/threads.cpp`

## 常见测试方式

### 1. 启动一个新的 Java 进程

这是最常见的 runtime 启动路径测试方式。测试通过 `ProcessTools.createTestJavaProcessBuilder(...)` 启动一个新的 JVM，因此会走到 `JNI_CreateJavaVM` 和 `Threads::create_vm`。

代表测试：

- `test/hotspot/jtreg/runtime/NMT/NMTInitializationTest.java`

运行：

```bash
make CONF=macosx-aarch64-server-slowdebug test TEST="test/hotspot/jtreg/runtime/NMT/NMTInitializationTest.java"
```

这个测试关注 NMT 在 VM 参数解析后、初始化阶段的状态，适合理解 `CreateJavaVM` 早期初始化。

### 2. native 程序直接调用 `JNI_CreateJavaVM`

这种方式更接近 `Threads::create_vm`。native 测试程序直接调用 JNI Invocation API。

代表测试：

- `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java`
- `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/exeGetCreatedJavaVMs.c`

运行：

```bash
make CONF=macosx-aarch64-server-slowdebug test TEST="test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java"
```

其中 native 代码会直接执行：

```c
JNI_CreateJavaVM(&vm, (void **)&env, &vm_args);
```

这个测试还覆盖并发创建 JVM 的场景：两个 native 线程竞争创建 JVM，失败的一方通过 `JNI_GetCreatedJavaVMs` 获取已创建的 JVM，并尝试 attach。

### 3. launcher / JLI 相关测试

JDK launcher 测试也可能通过 native launcher 或 JLI 路径间接覆盖 `JNI_CreateJavaVM`。

代表源码：

- `test/jdk/tools/launcher/exeJniInvocationTest.c`

这类测试更偏 launcher/JLI 行为，不是专门测试 `Threads::create_vm`，但同样会经过 VM 创建路径。

## GetCreatedJavaVMs 文件来源

看到这个文件：

```text
build/macosx-aarch64-server-slowdebug/images/test/hotspot/jtreg/native/GetCreatedJavaVMs
```

它不是源码文件，而是 HotSpot jtreg native 测试构建出的可执行文件。

对应关系：

```text
源码：
test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/exeGetCreatedJavaVMs.c

构建中间产物：
build/macosx-aarch64-server-slowdebug/support/test/hotspot/jtreg/native/bin/GetCreatedJavaVMs

test image 最终产物：
build/macosx-aarch64-server-slowdebug/images/test/hotspot/jtreg/native/GetCreatedJavaVMs
```

Java driver 通过下面的名字启动它：

```java
ProcessBuilder pb = ProcessTools.createNativeTestProcessBuilder("GetCreatedJavaVMs");
```

位置：

- `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java`

## native 测试命名规则

规则在：

- `make/common/TestFilesCompilation.gmk`

核心规则：

```make
$1_PREFIX = exe
...
unprefixed_name := $(patsubst $1_PREFIX%, %, $(name))
...
NAME := $(unprefixed_name)
```

含义：

- `exe*.c` / `exe*.cpp` 会被当作可执行程序编译。
- 生成的可执行文件会去掉 `exe` 前缀。
- 所以 `exeGetCreatedJavaVMs.c` 会生成 `GetCreatedJavaVMs`。

HotSpot jtreg native 测试构建入口：

- `make/test/JtregNativeHotspot.gmk`

其中有：

```make
HOTSPOT_JTREG_EXECUTABLES_JDK_LIBS_exeGetCreatedJavaVMs := java.base:libjvm
```

这表示 `exeGetCreatedJavaVMs.c` 这个 native executable 需要链接 `java.base:libjvm`。

## 学习建议

研究 `Threads::create_vm` 时，可以从这条线开始：

1. 读 `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/TestGetCreatedJavaVMs.java`。
2. 读 `test/hotspot/jtreg/runtime/jni/getCreatedJavaVMs/exeGetCreatedJavaVMs.c`。
3. 追到 `src/hotspot/share/prims/jni.cpp` 中的 `JNI_CreateJavaVM`。
4. 继续追到 `src/hotspot/share/runtime/threads.cpp` 中的 `Threads::create_vm`。
5. 对照 `make/common/TestFilesCompilation.gmk` 理解 native 测试如何构建。
6. 对照 `make/test/JtregNativeHotspot.gmk` 理解 HotSpot jtreg native 测试如何进入 test image。

