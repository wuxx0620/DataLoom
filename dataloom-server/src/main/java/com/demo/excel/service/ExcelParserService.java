package com.demo.excel.service;

import com.demo.excel.common.JsonHelper;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析引擎 — 流式 POI 解析 + 分块持久化
 */
@Service
public class ExcelParserService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParserService.class);

    /** 每块存储的最大行数（可按实际数据密度调整） */
    public static final int CHUNK_SIZE = 1000;

    @Autowired
    private ExcelSheetMapper sheetMapper;

    @Autowired
    private ExcelSheetChunkMapper chunkMapper;

    @Autowired
    private JsonHelper json;

    @Transactional(rollbackFor = Exception.class)
    public List<ExcelSheet> parseAndSave(InputStream is, ExcelDocument document) throws Exception {
        List<ExcelSheet> savedSheets = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(is)) {
            int sheetTotal = workbook.getNumberOfSheets();
            log.info("开始解析文档 [{}], 共 {} 个 Sheet", document.getName(), sheetTotal);

            for (int si = 0; si < sheetTotal; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                log.info("  → 解析 Sheet[{}]: {}", si, sheet.getSheetName());

                ExcelSheet sheetEntity = saveSheetMeta(sheet, document, si, sheetTotal);
                savedSheets.add(sheetEntity);

                saveSheetChunks(sheet, workbook, sheetEntity);
            }
        }

        log.info("文档 [{}] 解析完成，共 {} 个 Sheet", document.getName(), savedSheets.size());
        return savedSheets;
    }

    private ExcelSheet saveSheetMeta(Sheet sheet, ExcelDocument document, int sheetIndex, int totalSheets) {
        ExcelSheet sheetEntity = new ExcelSheet();
        sheetEntity.setDocumentId(document.getId());
        sheetEntity.setSheetIndex(sheetIndex);
        sheetEntity.setSheetName(sheet.getSheetName());
        sheetEntity.setActive(sheetIndex == 0 ? 1 : 0);
        sheetEntity.setStatus(1);

        int maxRow = sheet.getLastRowNum();
        int maxCol = calcMaxCol(sheet);
        sheetEntity.setTotalRows(maxRow + 1);
        sheetEntity.setTotalCols(maxCol);

        ObjectNode mergeConfig = buildMergeConfig(sheet);
        sheetEntity.setMergeConfigJson(json.toJson(mergeConfig));

        ObjectNode columnLen = buildColumnLen(sheet, maxCol);
        sheetEntity.setColumnLenJson(json.toJson(columnLen));

        ObjectNode rowLen = buildRowLen(sheet);
        sheetEntity.setRowLenJson(json.toJson(rowLen));

        ObjectNode config = json.createObject();
        config.set("merge", mergeConfig);
        config.set("columnlen", columnLen);
        config.set("rowlen", rowLen);
        sheetEntity.setConfigJson(json.toJson(config));

        sheetEntity.setChunkCount(0);

        sheetMapper.insert(sheetEntity);
        log.info("    Sheet 元信息已保存: id={}, rows={}, cols={}", sheetEntity.getId(), maxRow + 1, maxCol);
        return sheetEntity;
    }

    private void saveSheetChunks(Sheet sheet, Workbook workbook, ExcelSheet sheetEntity) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        int lastRowNum = sheet.getLastRowNum();

        Map<Integer, List<ObjectNode>> chunkBuffer = new LinkedHashMap<>();

        for (int rowIdx = 0; rowIdx <= lastRowNum; rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) {
                continue;
            }

            int chunkIndex = rowIdx / CHUNK_SIZE;
            List<ObjectNode> buffer = chunkBuffer.computeIfAbsent(chunkIndex, k -> new ArrayList<>(CHUNK_SIZE * 10));

            for (Cell cell : row) {
                ObjectNode vObj = buildCellValue(cell, evaluator);
                if (vObj != null) {
                    ObjectNode cellItem = json.createObject();
                    cellItem.put("r", cell.getRowIndex());
                    cellItem.put("c", cell.getColumnIndex());
                    cellItem.set("v", vObj);
                    buffer.add(cellItem);
                }
            }
        }

        int chunkCount = 0;
        for (Map.Entry<Integer, List<ObjectNode>> entry : chunkBuffer.entrySet()) {
            int chunkIndex = entry.getKey();
            List<ObjectNode> cells = entry.getValue();
            if (cells.isEmpty()) {
                continue;
            }

            ExcelSheetChunk chunk = new ExcelSheetChunk();
            chunk.setDocumentId(sheetEntity.getDocumentId());
            chunk.setSheetId(sheetEntity.getId());
            chunk.setChunkIndex(chunkIndex);
            chunk.setRowStart(chunkIndex * CHUNK_SIZE);
            chunk.setRowEnd((chunkIndex + 1) * CHUNK_SIZE - 1);
            chunk.setCelldataJson(json.toJson(cells));
            chunkMapper.insert(chunk);

            log.debug("    Chunk[{}] 已写入: rows {}-{}, cellCount={}", chunkIndex, chunk.getRowStart(), chunk.getRowEnd(),
                    cells.size());
            chunkCount = chunkIndex + 1;
        }

        ExcelSheet update = new ExcelSheet();
        update.setId(sheetEntity.getId());
        update.setChunkCount(chunkCount);
        sheetMapper.updateById(update);
        sheetEntity.setChunkCount(chunkCount);

        log.info("    Sheet [{}] 分块完成，共 {} 块", sheetEntity.getSheetName(), chunkCount);
    }

    private ObjectNode buildCellValue(Cell cell, FormulaEvaluator evaluator) {
        ObjectNode v = json.createObject();
        ObjectNode ct = json.createObject();

        try {
            switch (cell.getCellType()) {
                case STRING: {
                    String val = cell.getStringCellValue();
                    if (val == null || val.isEmpty()) {
                        return null;
                    }
                    v.put("v", val);
                    v.put("m", val);
                    ct.put("fa", "General");
                    ct.put("t", "s");
                    break;
                }
                case NUMERIC: {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        String dateStr = sdf.format(cell.getDateCellValue());
                        v.put("v", dateStr);
                        v.put("m", dateStr);
                        ct.put("fa", "yyyy-MM-dd");
                        ct.put("t", "d");
                    } else {
                        double num = cell.getNumericCellValue();
                        v.put("v", num);
                        v.put("m", formatNum(num));
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    }
                    break;
                }
                case BOOLEAN: {
                    boolean b = cell.getBooleanCellValue();
                    v.put("v", b);
                    v.put("m", b ? "TRUE" : "FALSE");
                    ct.put("fa", "General");
                    ct.put("t", "b");
                    break;
                }
                case FORMULA: {
                    try {
                        CellValue cv = evaluator.evaluate(cell);
                        v.put("f", "=" + cell.getCellFormula());
                        if (cv != null && cv.getCellType() == CellType.NUMERIC) {
                            v.put("v", cv.getNumberValue());
                            v.put("m", formatNum(cv.getNumberValue()));
                        } else {
                            String sv = cv != null ? cv.getStringValue() : "";
                            v.put("v", sv);
                            v.put("m", sv);
                        }
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    } catch (Exception fe) {
                        String raw = cell.getCellFormula();
                        v.put("v", "=" + raw);
                        v.put("m", "=" + raw);
                        ct.put("fa", "General");
                        ct.put("t", "s");
                    }
                    break;
                }
                case BLANK:
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }

        v.set("ct", ct);
        return v;
    }

    private int calcMaxCol(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) {
            if (row.getLastCellNum() > max) {
                max = row.getLastCellNum();
            }
        }
        return max;
    }

    private ObjectNode buildMergeConfig(Sheet sheet) {
        ObjectNode mergeConfig = json.createObject();
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            String key = range.getFirstRow() + "_" + range.getFirstColumn();
            ObjectNode item = json.createObject();
            item.put("r", range.getFirstRow());
            item.put("c", range.getFirstColumn());
            item.put("rs", range.getLastRow() - range.getFirstRow() + 1);
            item.put("cs", range.getLastColumn() - range.getFirstColumn() + 1);
            mergeConfig.set(key, item);
        }
        return mergeConfig;
    }

    private ObjectNode buildColumnLen(Sheet sheet, int maxCol) {
        ObjectNode colWidths = json.createObject();
        try {
            for (int ci = 0; ci < maxCol; ci++) {
                double width = sheet.getColumnWidthInPixels(ci);
                colWidths.put(String.valueOf(ci), (int) Math.max(width, 72));
            }
        } catch (Exception ignored) {
            // 部分 sheet 可能无行数据，忽略
        }
        return colWidths;
    }

    private ObjectNode buildRowLen(Sheet sheet) {
        ObjectNode rowHeights = json.createObject();
        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            float height = row.getHeightInPoints();
            if (height > 0) {
                rowHeights.put(String.valueOf(row.getRowNum()), Math.round(height / 0.75f));
            }
        }
        return rowHeights;
    }

    private String formatNum(double num) {
        if (num == (long) num) {
            return String.valueOf((long) num);
        }
        return String.valueOf(num);
    }
}
