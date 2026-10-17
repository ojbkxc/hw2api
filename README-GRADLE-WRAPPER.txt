Gradle Wrapper 说明（本仓库不含 gradlew 脚本与 gradle-wrapper.jar）
=================================================================

因本仓库在无 JDK/Gradle 环境的机器上产出，按约定不伪造 gradle-wrapper.jar 二进制，
只提供 gradle/wrapper/gradle-wrapper.properties（指向 Gradle 8.9）。

首次使用 Android Studio 打开本工程后，有两种补全方式：

方式一（推荐）：
  Android Studio → Open → 选择本目录 → Gradle Sync 时 AS 会提示
  "Gradle wrapper not found"，按提示使用 AS 内置 Gradle 或自动补全 wrapper。

方式二（命令行）：
  在 Android Studio Terminal（或任何装了 Gradle 8.x 的终端）执行：
      gradle wrapper --gradle-version 8.9
  该命令会生成 gradlew / gradlew.bat / gradle/wrapper/gradle-wrapper.jar，
  之后即可使用 ./gradlew 或 gradlew.bat 构建验证。

版本要求：Gradle 8.9+ / AGP 8.7.x / Kotlin 2.0.21 / JDK 17（AS 自带）。