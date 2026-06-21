# Wave 0 实施记录

> 状态：已完成 | 关联：[技术组件升级方案.md](./技术组件升级方案.md)

---

## 目标

建立后续 Wave 1～4 升级的基线与防护：

1. Spring Profile 拆分（dev / prod）
2. SpreadsheetAdapter 抽象层 + LuckysheetAdapter 实现
3. SheetEditor.vue 解耦 Luckysheet 全局对象
4. Golden Path 回归清单与冒烟脚本

---

## 已完成项

| 项 | 文件/改动 | 说明 |
|----|-----------|------|
| Profile 公共配置 | `application.yml` | 抽取公共项，`spring.profiles.active` 默认 `dev` |
| 开发 Profile | `application-dev.yml` | H2 + `schema.sql` 自动建表 |
| 生产 Profile 骨架 | `application-prod.yml` | MySQL 连接占位，Wave 2 启用 |
| 适配层工厂 | `src/adapters/spreadsheet/index.js` | `createSpreadsheetAdapter()` |
| Luckysheet 实现 | `src/adapters/luckysheet/*` | init/destroy/保存/导出/结构快照 |
| 编辑器解耦 | `src/views/SheetEditor.vue` | 通过 Adapter 调用，不再直接使用 `window.luckysheet` |
| 回归清单 | `docs/Wave0-GoldenPath-回归清单.md` | 6 项 Golden Path + Wave 0 专项 |
| 冒烟脚本 | `scripts/wave0-smoke.sh` | 校验文档列表 API |

---

## 基线 Tag

```bash
git tag -a baseline-v2.0.0-pre-upgrade -m "Wave 0 baseline before tech stack upgrade"
git push origin baseline-v2.0.0-pre-upgrade
```

---

## 验收

- [x] `application-dev.yml` / `application-prod.yml` 已创建
- [x] SpreadsheetAdapter + LuckysheetAdapter 已落地
- [x] SheetEditor.vue 已接入 Adapter
- [x] Golden Path 文档与 smoke 脚本已添加
- [ ] 手动 Golden Path GP-1～5 已执行并记录（需本地环境）
- [ ] 基线 tag 已推送

---

## 下一步（Wave 1）

参见 [技术组件升级方案.md](./技术组件升级方案.md) 第五章：

- JDK 17 + Spring Boot 3.3
- FastJSON → Jackson
- Apache POI 5.x
- 移除 EasyExcel / commons-fileupload
