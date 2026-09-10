# ALInvite 构建教程

## 📋 构建环境要求

### 系统要求
- **操作系统**: Windows 10/11, macOS 10.15+, Linux (Ubuntu 18.04+)
- **Java**: JDK 21 或更高版本
- **构建工具**: Apache Maven 3.6.0+

### 软件安装

#### 1. 安装 Java Development Kit (JDK 21)

**Windows:**
```bash
# 下载并安装 OpenJDK 21
# 下载地址: https://adoptium.net/
# 设置环境变量
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.2.13-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
```

**Linux/macOS:**
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# macOS (使用 Homebrew)
brew install openjdk@21

# 验证安装
java -version
javac -version
```

#### 2. 安装 Apache Maven

**Windows:**
```bash
# 下载 Maven: https://maven.apache.org/download.cgi
# 解压到 C:\Program Files\apache-maven-3.9.6
# 设置环境变量
set MAVEN_HOME=C:\Program Files\apache-maven-3.9.6
set PATH=%MAVEN_HOME%\bin;%PATH%
```

**Linux/macOS:**
```bash
# Ubuntu/Debian
sudo apt install maven

# macOS (使用 Homebrew)
brew install maven

# 验证安装
mvn -version
```

## 🔨 构建步骤

### 方法一：使用 Maven 命令行构建

#### 1. 克隆或下载源代码
```bash
# 如果从 GitHub 克隆
git clone https://github.com/yourusername/ALInvite.git
cd ALInvite

# 或者直接下载 ZIP 文件并解压
```

#### 2. 清理并构建项目
```bash
# 清理之前的构建文件
mvn clean

# 编译源代码
mvn compile

# 打包生成插件 JAR 文件
mvn package
```

#### 3. 构建结果
构建成功后，在 `target/` 目录下会生成：
- `ALInvite-2.1.0.jar` - shaded 主插件文件（部署此文件）
- `original-ALInvite-2.1.0.jar` - 未 shaded 的原始构建文件

### 方法二：使用 IDE 构建

#### IntelliJ IDEA
1. 打开项目: `File` → `Open` → 选择项目文件夹
2. 等待 Maven 依赖自动下载
3. 构建: `Build` → `Build Project` (Ctrl+F9)
4. 或者使用 Maven 面板: 双击 `Lifecycle` → `package`

#### Eclipse
1. 导入项目: `File` → `Import` → `Maven` → `Existing Maven Projects`
2. 选择项目文件夹
3. 右键项目 → `Run As` → `Maven build`
4. 目标输入: `clean package`

### 方法三：一键构建脚本

#### Windows (build.bat)
```batch
@echo off
echo 开始构建 ALInvite 插件...

mvn clean package

if %ERRORLEVEL% EQU 0 (
    echo 构建成功！插件文件在 target/ 目录
    echo 文件: ALInvite-2.1.0.jar
) else (
    echo 构建失败，请检查错误信息
)

pause
```

#### Linux/macOS (build.sh)
```bash
#!/bin/bash
echo "开始构建 ALInvite 插件..."

mvn clean package

if [ $? -eq 0 ]; then
    echo "构建成功！插件文件在 target/ 目录"
    echo "文件: ALInvite-2.1.0.jar"
else
    echo "构建失败，请检查错误信息"
fi
```

## 🔧 常见构建问题解决

### 问题1: Java 版本不兼容
**错误信息:**
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.12.1:compile
```

**解决方案:**
```bash
# 检查当前 Java 版本
java -version

# 如果版本低于 21，安装 JDK 21
# 或者设置 JAVA_HOME 环境变量指向 JDK 21
```

### 问题2: Maven 依赖下载失败
**错误信息:**
```
[ERROR] Failed to execute goal on project ALInvite: Could not resolve dependencies
```

**解决方案:**
```bash
# 清理 Maven 缓存
mvn dependency:purge-local-repository

# 重新下载依赖
mvn dependency:resolve

# 或者使用国内镜像源
# 在 ~/.m2/settings.xml 中配置阿里云镜像
```

### 问题3: 内存不足
**错误信息:**
```
java.lang.OutOfMemoryError: Java heap space
```

**解决方案:**
```bash
# 增加 Maven 内存限制
set MAVEN_OPTS=-Xmx2g -Xms512m
mvn clean package
```

### 问题4: 编码问题
**错误信息:**
```
[ERROR] 编码 GBK 的不可映射字符
```

**解决方案:**
```bash
# 设置编码参数
mvn clean package -Dfile.encoding=UTF-8
```

## 📦 自定义构建配置

### 修改版本号
编辑 `pom.xml`:
```xml
<version>2.1.0</version>  <!-- 发布版本号，需与 pom.xml 保持一致 -->
```

### 排除依赖项
如果需要精简插件大小，可以在 `pom.xml` 中排除不必要的依赖：
```xml
<exclusions>
    <exclusion>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </exclusion>
</exclusions>
```

### 构建不同版本
```bash
# 仅编译，不打包
mvn compile

# 运行测试
mvn test

# 生成 Javadoc 文档
mvn javadoc:javadoc

# 安装到本地 Maven 仓库
mvn install
```

## 🚀 部署和使用

### 1. 部署到服务器
```bash
# 将构建的 JAR 文件复制到服务器 plugins 目录
cp target/ALInvite-2.1.0.jar /path/to/server/plugins/
```

### 2. 重启服务器
```bash
# 重启 Minecraft 服务器使插件生效
./restart.sh  # 或相应的重启命令
```

### 3. 验证安装
```bash
# 在游戏内验证插件是否正常工作
/alinvite help
/alinvite code
```

## 📝 开发建议

### 代码规范
- 遵循 Java 命名规范
- 添加适当的注释
- 使用 try-with-resources 处理资源
- 异步操作使用 CompletableFuture

### 测试建议
- 编写单元测试
- 测试不同权限场景
- 验证跨服功能
- 测试数据库连接

### 性能优化
- 使用缓存减少数据库查询
- 异步处理耗时操作
- 合理设置连接池大小

---

## 📞 技术支持

如果遇到构建问题，请：
1. 检查错误信息
2. 确认环境配置正确
3. 查看 [GitHub Issues](https://github.com/yourusername/ALInvite/issues)
4. 提交新的 Issue 描述问题

**祝您构建成功！** 🎉