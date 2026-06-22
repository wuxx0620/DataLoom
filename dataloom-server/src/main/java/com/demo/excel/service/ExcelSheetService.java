package com.demo.excel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.demo.excel.common.JsonHelper;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel Sheet 及数据分块查询服务
 */
@Service
public class ExcelSheetService {

    @Autowired
    private ExcelSheetMapper sheetMapper;

    @Autowired
    private ExcelSheetChunkMapper chunkMapper;

    @Autowired
    private ExcelDocumentService documentService;

    @Autowired
    private JsonHelper json;

    public List<ExcelSheet> listSheetsByDocumentId(Long documentId) {
        QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId)
                .eq("status", 1)
                .orderByAsc("sheet_index");
        return sheetMapper.selectList(qw);
    }

    public List<ExcelSheetChunk> listChunksBySheetId(Long sheetId) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId)
                .orderByAsc("chunk_index");
        return chunkMapper.selectList(qw);
    }

    public void deleteByDocumentId(Long documentId) {
        ExcelSheet update = new ExcelSheet();
        update.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(update, sheetQw);

        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateCells(Long documentId, List<Map<String, Object>> updates) {
        Map<String, List<Map<String, Object>>> chunkGroup = new HashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (Map<String, Object> update : updates) {
            Long sheetId = Long.parseLong(update.get("sheetId").toString());
            int r = Integer.parseInt(update.get("r").toString());
            int targetChunkIndex = r / chunkSize;
            String key = sheetId + "_" + targetChunkIndex;
            chunkGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(update);
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : chunkGroup.entrySet()) {
            String[] parts = entry.getKey().split("_");
            Long sheetId = Long.parseLong(parts[0]);
            int targetChunkIndex = Integer.parseInt(parts[1]);

            QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
            qw.eq("sheet_id", sheetId).eq("chunk_index", targetChunkIndex);
            ExcelSheetChunk chunk = chunkMapper.selectOne(qw);

            ArrayNode cellArray;
            boolean isNewChunk = false;

            if (chunk == null) {
                isNewChunk = true;
                chunk = new ExcelSheetChunk();
                chunk.setDocumentId(documentId);
                chunk.setSheetId(sheetId);
                chunk.setChunkIndex(targetChunkIndex);
                chunk.setRowStart(targetChunkIndex * chunkSize);
                chunk.setRowEnd((targetChunkIndex + 1) * chunkSize - 1);
                cellArray = json.createArray();
            } else {
                cellArray = json.parseArrayOrEmpty(chunk.getCelldataJson());
            }

            Map<String, Integer> cellIndex = new HashMap<>(cellArray.size());
            for (int i = 0; i < cellArray.size(); i++) {
                ObjectNode cell = json.getObjectNode(cellArray, i);
                if (cell == null) {
                    continue;
                }
                cellIndex.put(json.getInt(cell, "r") + "_" + json.getInt(cell, "c"), i);
            }

            Set<Integer> removeSet = new HashSet<>();

            for (Map<String, Object> update : entry.getValue()) {
                int r = Integer.parseInt(update.get("r").toString());
                int c = Integer.parseInt(update.get("c").toString());
                String cellKey = r + "_" + c;
                Object vObj = update.get("v");
                ObjectNode cellValue = vObj != null ? json.toObjectNode(vObj) : null;

                Integer idx = cellIndex.get(cellKey);
                if (idx != null) {
                    if (json.isEmptyObject(cellValue)) {
                        removeSet.add(idx);
                        cellIndex.remove(cellKey);
                    } else {
                        json.getObjectNode(cellArray, idx).set("v", cellValue);
                    }
                } else if (!json.isEmptyObject(cellValue)) {
                    ObjectNode newCell = json.createObject();
                    newCell.put("r", r);
                    newCell.put("c", c);
                    newCell.set("v", cellValue);
                    cellArray.add(newCell);
                    cellIndex.put(cellKey, cellArray.size() - 1);
                }
            }

            if (!removeSet.isEmpty()) {
                List<Integer> sortedIndices = new ArrayList<>(removeSet);
                sortedIndices.sort(Collections.reverseOrder());
                for (int idx : sortedIndices) {
                    cellArray.remove(idx);
                }
            }

            chunk.setCelldataJson(json.toJson(cellArray));

            if (isNewChunk) {
                chunkMapper.insert(chunk);
                ExcelSheet sheet = sheetMapper.selectById(sheetId);
                if (sheet != null && sheet.getChunkCount() <= targetChunkIndex) {
                    ExcelSheet sheetUpdate = new ExcelSheet();
                    sheetUpdate.setId(sheetId);
                    sheetUpdate.setChunkCount(targetChunkIndex + 1);
                    sheetMapper.updateById(sheetUpdate);
                }
            } else {
                chunkMapper.updateById(chunk);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<Integer, Long> replaceWorkbook(Long documentId, List<Map<String, Object>> workbookSheets) {
        if (workbookSheets == null || workbookSheets.isEmpty()) {
            throw new IllegalArgumentException("workbook sheets must not be empty");
        }

        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);

        ExcelSheet deleted = new ExcelSheet();
        deleted.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(deleted, sheetQw);

        List<String> sheetNames = new ArrayList<>();
        int visibleIndex = 0;
        Map<Integer, Long> sheetIdMapping = new LinkedHashMap<>();

        for (Map<String, Object> rawSheet : workbookSheets) {
            if (rawSheet == null) {
                continue;
            }

            String name = stringValue(rawSheet.get("name"), "Sheet" + (visibleIndex + 1));
            sheetNames.add(name);

            ObjectNode config = toJsonObject(rawSheet.get("config"));
            ObjectNode hyperlink = toJsonObject(rawSheet.get("hyperlink"));
            if (json.isEmptyObject(hyperlink)) {
                hyperlink = json.createObject();
            }
            ObjectNode images = toJsonObject(rawSheet.get("images"));
            if (json.isEmptyObject(images)) {
                images = json.createObject();
            }
            ArrayNode conditionFormat = toJsonArray(rawSheet.get("luckysheet_conditionformat_save"));
            if (conditionFormat.isEmpty()) {
                conditionFormat = json.createArray();
            }
            ArrayNode chart = toJsonArray(rawSheet.get("chart"));
            if (chart.isEmpty()) {
                chart = json.createArray();
            }

            ObjectNode merge = json.getObjectNode(config, "merge");
            ObjectNode columnLen = json.getObjectNode(config, "columnlen");
            ObjectNode rowLen = json.getObjectNode(config, "rowlen");

            if (merge == null) {
                merge = json.createObject();
            }
            if (columnLen == null) {
                columnLen = json.createObject();
            }
            if (rowLen == null) {
                rowLen = json.createObject();
            }
            config.set("merge", merge);
            config.set("columnlen", columnLen);
            config.set("rowlen", rowLen);

            ArrayNode celldata = resolveCelldata(rawSheet);
            int totalRows = intValue(rawSheet.get("row"), calcTotalRows(rawSheet.get("data"), celldata));
            int totalCols = intValue(rawSheet.get("column"), calcTotalCols(rawSheet.get("data"), celldata));

            ExcelSheet sheet = new ExcelSheet();
            sheet.setDocumentId(documentId);
            sheet.setSheetIndex(visibleIndex);
            sheet.setSheetName(name);
            sheet.setTotalRows(Math.max(totalRows, 1));
            sheet.setTotalCols(Math.max(totalCols, 1));
            sheet.setChunkCount(0);
            sheet.setMergeConfigJson(json.toJson(merge));
            sheet.setColumnLenJson(json.toJson(columnLen));
            sheet.setRowLenJson(json.toJson(rowLen));
            sheet.setConfigJson(json.toJson(config));
            sheet.setHyperlinkConfigJson(json.toJson(hyperlink));
            sheet.setImagesConfigJson(json.toJson(images));
            sheet.setConditionFormatJson(json.toJson(conditionFormat));
            sheet.setChartJson(json.toJson(chart));
            sheet.setActive(intValue(rawSheet.get("status"), visibleIndex == 0 ? 1 : 0));
            sheet.setStatus(1);
            sheetMapper.insert(sheet);

            sheetIdMapping.put(visibleIndex, sheet.getId());

            int chunkCount = saveCelldataChunks(documentId, sheet.getId(), celldata, sheet.getTotalRows());
            ExcelSheet update = new ExcelSheet();
            update.setId(sheet.getId());
            update.setChunkCount(chunkCount);
            sheetMapper.updateById(update);

            visibleIndex++;
        }

        if (sheetNames.isEmpty()) {
            throw new IllegalArgumentException("workbook contains no valid sheets");
        }

        documentService.updateSheetMeta(documentId, sheetNames.size(), json.toJson(sheetNames));
        return sheetIdMapping;
    }

    private int saveCelldataChunks(Long documentId, Long sheetId, ArrayNode celldata, int totalRows) {
        Map<Integer, ArrayNode> chunks = new LinkedHashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (int i = 0; i < celldata.size(); i++) {
            ObjectNode cell = json.getObjectNode(celldata, i);
            if (cell == null) {
                continue;
            }
            int row = json.getInt(cell, "r");
            int chunkIndex = row / chunkSize;
            chunks.computeIfAbsent(chunkIndex, key -> json.createArray()).add(cell);
        }

        if (chunks.isEmpty()) {
            return Math.max(1, (int) Math.ceil(Math.max(totalRows, 1) / (double) chunkSize));
        }

        for (Map.Entry<Integer, ArrayNode> entry : chunks.entrySet()) {
            int chunkIndex = entry.getKey();
            ExcelSheetChunk chunk = new ExcelSheetChunk();
            chunk.setDocumentId(documentId);
            chunk.setSheetId(sheetId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setRowStart(chunkIndex * chunkSize);
            chunk.setRowEnd((chunkIndex + 1) * chunkSize - 1);
            chunk.setCelldataJson(json.toJson(entry.getValue()));
            chunkMapper.insert(chunk);
        }

        return chunks.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
    }

    private ArrayNode resolveCelldata(Map<String, Object> rawSheet) {
        ArrayNode celldata = toJsonArray(rawSheet.get("celldata"));
        if (!celldata.isEmpty()) {
            return normalizeCelldata(celldata);
        }
        return dataMatrixToCelldata(rawSheet.get("data"));
    }

    private ArrayNode normalizeCelldata(ArrayNode celldata) {
        ArrayNode normalized = json.createArray();
        for (int i = 0; i < celldata.size(); i++) {
            ObjectNode cell = json.getObjectNode(celldata, i);
            if (cell == null || !cell.has("r") || !cell.has("c")) {
                continue;
            }
            JsonNode value = cell.get("v");
            if (isEmptyCellValue(value)) {
                continue;
            }
            normalized.add(cell);
        }
        return normalized;
    }

    private ArrayNode dataMatrixToCelldata(Object dataObj) {
        ArrayNode rows = toJsonArray(dataObj);
        ArrayNode celldata = json.createArray();

        for (int r = 0; r < rows.size(); r++) {
            ArrayNode row = json.getArrayNode(rows, r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.size(); c++) {
                JsonNode value = row.get(c);
                if (isEmptyCellValue(value)) {
                    continue;
                }

                ObjectNode cell = json.createObject();
                cell.put("r", r);
                cell.put("c", c);
                cell.set("v", toJsonObject(value));
                celldata.add(cell);
            }
        }
        return celldata;
    }

    private boolean isEmptyCellValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (value.isObject()) {
            return value.isEmpty();
        }
        return false;
    }

    private int calcTotalRows(Object dataObj, ArrayNode celldata) {
        ArrayNode rows = toJsonArray(dataObj);
        int max = rows.size();
        for (int i = 0; i < celldata.size(); i++) {
            ObjectNode cell = json.getObjectNode(celldata, i);
            if (cell != null) {
                max = Math.max(max, json.getInt(cell, "r") + 1);
            }
        }
        return max;
    }

    private int calcTotalCols(Object dataObj, ArrayNode celldata) {
        ArrayNode rows = toJsonArray(dataObj);
        int max = 0;
        for (int r = 0; r < rows.size(); r++) {
            ArrayNode row = json.getArrayNode(rows, r);
            if (row != null) {
                max = Math.max(max, row.size());
            }
        }
        for (int i = 0; i < celldata.size(); i++) {
            ObjectNode cell = json.getObjectNode(celldata, i);
            if (cell != null) {
                max = Math.max(max, json.getInt(cell, "c") + 1);
            }
        }
        return max;
    }

    private ObjectNode toJsonObject(Object value) {
        if (value == null) {
            return json.createObject();
        }
        if (value instanceof ObjectNode objectNode) {
            return objectNode;
        }
        if (value instanceof JsonNode jsonNode && jsonNode.isObject()) {
            return (ObjectNode) jsonNode;
        }
        return json.toObjectNode(value);
    }

    private ArrayNode toJsonArray(Object value) {
        if (value == null) {
            return json.createArray();
        }
        if (value instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return json.toArrayNode(value);
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text.trim().isEmpty() ? defaultValue : text;
    }
}
