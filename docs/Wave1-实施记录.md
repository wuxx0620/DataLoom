# Wave 1 实施记录

> 状态：已完成（代码） | 待本地 JDK 17 验证 | 关联：[技术组件升级方案.md](./技术组件升级方案.md)

---

## 目标

后端基础栈升级：

1. JDK 17 + Spring Boot 3.3
2. FastJSON → Jackson（`JsonHelper` 统一封装）
3. MyBatis-Plus 3.5（Spring Boot 3 starter）
4. Apache POI 5.2.5
5. 移除 FastJSON / EasyExcel / commons-fileupload
6. Dockerfile.server → Java 17

---

## 已完成项

| 项 | 文件/改动 | 说明 |
|----|-----------|------|
| 依赖升级 | `dataloom-server/pom.xml` | SB 3.3.7、JDK 17、MP 3.5.9、POI 5.2.5 |
| JSON 封装 | `common/JsonHelper.java` | 替代 FastJSON 的 parse/toJson 工具 |
| Sheet 服务 | `ExcelSheetService.java` | 全量迁移 Jackson `ObjectNode`/`ArrayNode` |
| 解析服务 | `ExcelParserService.java` | 同上 |
| 文档接口 | `ExcelDocumentController.java` | 注入 `JsonHelper`，移除 FastJSON |
| 上传接口 | `ExcelFileController.java` | 移除 EasyExcel dead import |
| CORS | `CorsConfig.java` | `allowedOriginPatterns("*")`（SB3 推荐） |
| 单元测试 | `ExcelSheetServiceTest.java` | JUnit 5 + Jackson |
| Docker | `deplay/Dockerfile.server` | `eclipse-temurin:17` 构建与运行 |

---

## 版本对照

| 组件 | Wave 0 | Wave 1 |
|------|--------|--------|
| JDK | 8 | **17** |
| Spring Boot | 2.1.15 | **3.3.7** |
| MyBatis-Plus | 3.3.2 | **3.5.9**（boot3-starter） |
| POI | 4.1.2 | **5.2.5** |
| JSON | FastJSON 1.2.68 | **Jackson** |
| EasyExcel | 2.2.11（未用） | **已移除** |
| commons-fileupload | 1.4 | **已移除** |

---

## 本地验证

**前置条件：JDK 17**（Spring Boot 3 不支持 JDK 8）

```bash
# 确认 Java 版本
java -version   # 应显示 17.x

cd dataloom-server
mvn clean test

# 启动开发环境（H2）
mvn spring-boot:run

# Golden Path 冒烟
bash scripts/wave0-smoke.sh
```

**Docker 验证（可选）：**

```bash
docker compose -f deplay/docker-compose.yml build dataloom-server
```

---

## 验收清单

- [x] 依赖树无 FastJSON / EasyExcel / commons-fileupload
- [x] `javax.*` 零残留
- [x] `JsonHelper` 已落地并接入核心 Service/Controller
- [x] JUnit 5 测试已迁移
- [x] Dockerfile.server 已切换 Java 17
- [ ] `mvn clean test` 全绿（需本机 JDK 17）
- [ ] Golden Path 6 项回归通过
- [ ] Docker 镜像可启动，端口 9191 正常

---

## 下一步（Wave 2）

参见 [技术组件升级方案.md](./技术组件升级方案.md) 第六章：

- MySQL 8 + Flyway 迁移脚本
- `application-prod.yml` 完善
- Docker Compose 增加 MySQL 服务
- 图片/附件 URL 化
