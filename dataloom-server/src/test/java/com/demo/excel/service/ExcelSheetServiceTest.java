package com.demo.excel.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.excel.common.JsonHelper;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExcelSheetServiceTest {

    @Mock
    private ExcelSheetMapper sheetMapper;

    @Mock
    private ExcelSheetChunkMapper chunkMapper;

    @Mock
    private ExcelDocumentService documentService;

    private ExcelSheetService sheetService;

    private final JsonHelper json = new JsonHelper(new ObjectMapper());

    @BeforeEach
    void setUp() {
        sheetService = new ExcelSheetService();
        ReflectionTestUtils.setField(sheetService, "sheetMapper", sheetMapper);
        ReflectionTestUtils.setField(sheetService, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(sheetService, "documentService", documentService);
        ReflectionTestUtils.setField(sheetService, "json", json);
    }

    @Test
    void replaceWorkbookRebuildsSheetsChunksAndDocumentMetadata() {
        ObjectNode firstCell = json.createObject();
        firstCell.put("v", "A1");
        firstCell.put("m", "A1");

        ObjectNode secondCell = json.createObject();
        secondCell.put("v", 42);
        secondCell.put("m", "42");

        ArrayNode firstRow = json.createArray();
        firstRow.add(firstCell);

        ArrayNode secondRow = json.createArray();
        secondRow.addNull();
        secondRow.add(secondCell);

        ObjectNode config = json.createObject();
        ObjectNode columnLen = json.createObject();
        columnLen.put("0", 120);
        config.set("columnlen", columnLen);

        ObjectNode rowLen = json.createObject();
        rowLen.put("0", 28);
        config.set("rowlen", rowLen);

        Map<String, Object> sheet = new java.util.LinkedHashMap<>();
        sheet.put("name", "Budget");
        sheet.put("index", "sheet-1");
        sheet.put("status", 1);
        sheet.put("order", 0);
        sheet.put("config", config);
        sheet.put("data", Arrays.asList(firstRow, secondRow));

        sheetService.replaceWorkbook(7L, Collections.singletonList(sheet));

        verify(chunkMapper).delete(any(Wrapper.class));
        verify(sheetMapper).update(any(ExcelSheet.class), any(Wrapper.class));

        ArgumentCaptor<ExcelSheet> sheetCaptor = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).insert(sheetCaptor.capture());
        ExcelSheet savedSheet = sheetCaptor.getValue();
        assertEquals(Long.valueOf(7L), savedSheet.getDocumentId());
        assertEquals("Budget", savedSheet.getSheetName());
        assertEquals(Integer.valueOf(1), savedSheet.getActive());
        assertEquals(Integer.valueOf(2), savedSheet.getTotalRows());
        assertEquals(Integer.valueOf(2), savedSheet.getTotalCols());
        assertEquals("{\"0\":120}", savedSheet.getColumnLenJson());
        assertEquals("{\"0\":28}", savedSheet.getRowLenJson());

        ArgumentCaptor<ExcelSheetChunk> chunkCaptor = ArgumentCaptor.forClass(ExcelSheetChunk.class);
        verify(chunkMapper, atLeastOnce()).insert(chunkCaptor.capture());
        ExcelSheetChunk chunk = chunkCaptor.getValue();
        assertEquals(Long.valueOf(7L), chunk.getDocumentId());
        assertNotNull(chunk.getCelldataJson());

        ArrayNode celldata = json.parseArrayOrEmpty(chunk.getCelldataJson());
        assertEquals(2, celldata.size());
        assertEquals(0, json.getInt(json.getObjectNode(celldata, 0), "r"));
        assertEquals(0, json.getInt(json.getObjectNode(celldata, 0), "c"));
        assertEquals(1, json.getInt(json.getObjectNode(celldata, 1), "r"));
        assertEquals(1, json.getInt(json.getObjectNode(celldata, 1), "c"));

        verify(documentService).updateSheetMeta(7L, 1, "[\"Budget\"]");
    }
}
