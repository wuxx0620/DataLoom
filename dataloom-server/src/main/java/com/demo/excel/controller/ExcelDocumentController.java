package com.demo.excel.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.excel.common.ApiResponse;
import com.demo.excel.common.JsonHelper;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.service.ExcelDocumentService;
import com.demo.excel.service.ExcelSheetService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Excel 文档接口 — 文档 CRUD + 工作簿保存 + 单元格批量更新
 */
@RestController
@RequestMapping("/api/excel/document")
public class ExcelDocumentController {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentController.class);

    @Autowired
    private ExcelDocumentService documentService;

    @Autowired
    private ExcelSheetService sheetService;

    @Autowired
    private JsonHelper json;

    @GetMapping("/list")
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int pageNum,
                               @RequestParam(defaultValue = "20") int pageSize) {
        Page<ExcelDocument> page = documentService.listByPage(pageNum, pageSize);

        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelDocument doc : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("name", doc.getName());
            item.put("sheetCount", doc.getSheetCount());
            item.put("sheetNames", doc.getSheetNames());
            item.put("version", doc.getVersion());
            item.put("fileSize", doc.getFileSize());
            item.put("createTime", doc.getCreateTime());
            item.put("updateTime", doc.getUpdateTime());
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("current", page.getCurrent());
        result.put("records", records);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        ExcelDocument doc = documentService.getById(id);
        if (doc == null) {
            return ApiResponse.fail(404, "文档不存在");
        }

        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(id);

        List<Map<String, Object>> sheetInfoList = new ArrayList<>();
        for (ExcelSheet s : sheets) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sheetId", s.getId());
            info.put("sheetIndex", s.getSheetIndex());
            info.put("sheetName", s.getSheetName());
            info.put("totalRows", s.getTotalRows());
            info.put("totalCols", s.getTotalCols());
            info.put("chunkCount", s.getChunkCount());
            info.put("active", s.getActive());

            ObjectNode mergeConfig = json.parseObjectOrEmpty(s.getMergeConfigJson());
            ObjectNode columnLen = json.parseObjectOrEmpty(s.getColumnLenJson());
            ObjectNode rowLen = json.parseObjectOrEmpty(s.getRowLenJson());
            ObjectNode config = json.parseObjectOrEmpty(s.getConfigJson());
            if (config.isEmpty()) {
                config.set("merge", mergeConfig);
                config.set("columnlen", columnLen);
                config.set("rowlen", rowLen);
            }
            info.put("config", config);
            info.put("mergeConfig", mergeConfig);
            info.put("columnLen", columnLen);
            info.put("rowLen", rowLen);

            info.put("hyperlink", json.parseObjectOrEmpty(s.getHyperlinkConfigJson()));
            info.put("images", json.parseObjectOrEmpty(s.getImagesConfigJson()));
            info.put("luckysheet_conditionformat_save", json.parseArrayOrEmpty(s.getConditionFormatJson()));
            info.put("chart", json.parseArrayOrEmpty(s.getChartJson()));

            sheetInfoList.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());
        result.put("name", doc.getName());
        result.put("sheetCount", doc.getSheetCount());
        result.put("version", doc.getVersion());
        result.put("sheets", sheetInfoList);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}/sheet/{sheetId}/all")
    public ApiResponse<?> loadAllCelldata(@PathVariable Long id,
                                          @PathVariable Long sheetId) {
        List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheetId);

        ArrayNode mergedCelldata = json.createArray();
        for (ExcelSheetChunk chunk : chunks) {
            try {
                ArrayNode cells = json.parseArrayOrEmpty(chunk.getCelldataJson());
                mergedCelldata.addAll(cells);
            } catch (Exception e) {
                log.warn("解析 chunk[{}] 失败: {}", chunk.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetId", sheetId);
        result.put("celldata", mergedCelldata);
        result.put("cellCount", mergedCelldata.size());
        return ApiResponse.ok(result);
    }

    @PutMapping("/{id}/cells/batch")
    public ApiResponse<?> batchUpdateCells(@PathVariable Long id,
                                           @RequestBody List<Map<String, Object>> updates) {
        try {
            if (updates == null || updates.isEmpty()) {
                return ApiResponse.ok("没有需要保存的修改");
            }
            sheetService.batchUpdateCells(id, updates);
            log.info("批量更新单元格成功: doc={}, count={}", id, updates.size());
            return ApiResponse.ok("保存成功");
        } catch (Exception e) {
            log.error("批量更新单元格失败: doc={}, error={}", id, e.getMessage());
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/workbook")
    public ApiResponse<?> saveWorkbook(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        try {
            Object sheetsObj = body.get("sheets");
            if (!(sheetsObj instanceof List)) {
                return ApiResponse.fail("sheets 不能为空");
            }

            List<Map<String, Object>> sheets = (List<Map<String, Object>>) sheetsObj;
            Map<Integer, Long> sheetIdMapping = sheetService.replaceWorkbook(id, sheets);

            log.info("工作簿全量保存成功: doc={}, sheetCount={}", id, sheets.size());

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("sheetCount", sheets.size());
            resultData.put("sheetIdMap", sheetIdMapping);
            return ApiResponse.ok("保存成功", resultData);
        } catch (Exception e) {
            log.error("工作簿全量保存失败: doc={}, error={}", id, e.getMessage(), e);
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/name")
    public ApiResponse<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            return ApiResponse.fail("名称不能为空");
        }
        ExcelDocument doc = documentService.getById(id);
        if (doc == null) {
            return ApiResponse.fail(404, "文档不存在");
        }
        documentService.rename(id, newName.trim());
        log.info("文档重命名成功: doc={}, name={}", id, newName.trim());
        return ApiResponse.ok("重命名成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        documentService.delete(id);
        sheetService.deleteByDocumentId(id);
        log.info("文档已删除: doc={}", id);
        return ApiResponse.ok("删除成功");
    }
}
