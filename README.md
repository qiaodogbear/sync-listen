# Sync Listen

Sync Listen 是一个 Android 多人同步听歌原型。房间成员共享播放列表，将本地 MP3/FLAC 上传到中心服务器并自动缓存到各设备，由房主控制各设备从本地文件大致同步播放。

## 当前状态

项目处于工程骨架阶段。详细进度、依赖和验收方法见 [TASKS.md](./TASKS.md)，中断恢复信息见 [EXECUTION_LOG.md](./EXECUTION_LOG.md)。

## 目录结构

```text
sync-listen/
  android-app/   Android 客户端
  backend/       Fastify 后端
  docs/          API、WebSocket、调试与验收文档
  TASKS.md       唯一任务进度清单
```

## 环境要求

- Node.js 24 或兼容版本
- npm 11 或兼容版本
- JDK 17 或兼容 Android Gradle Plugin 的版本
- Android SDK 35
- Android Studio 或 Android SDK 命令行工具

## 默认网络配置

- 后端默认监听：`0.0.0.0:3000`
- Android 模拟器访问宿主机：`http://10.0.2.2:3000`
- 真机访问：将服务器地址设置为运行后端电脑的局域网 IP，例如 `http://192.168.1.10:3000`

不要在客户端配置中使用 `localhost` 访问宿主机后端；在 Android 设备中，`localhost` 指设备自身。

## 后端运行

```powershell
cd backend
npm install
npm run db:migrate
npm run dev
```

健康检查：`http://127.0.0.1:3000/health`。

## Android 构建

Android Gradle 构建需要 Android Studio 内置 JBR 或兼容 JDK。当前项目路径包含中文，Windows 下 Gradle 单元测试存在类路径编码问题；使用 ASCII 驱动器映射执行 Android 验证：

```powershell
subst S: C:\Users\15224\Desktop\工程\sync-listen
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd S:\android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Debug APK 输出到 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

## 验证后端

```powershell
cd backend
npm run lint
npm run typecheck
npm test
npm run build
```
