package com.demo.excel.controller;

import com.demo.excel.common.ApiResponse;
import com.demo.excel.common.JsonHelper;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.service.ExcelDocumentService;
import com.demo.excel.service.ExcelParserService;
import com.demo.excel.service.ExcelSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Excel 文件上传接口
 * <p>
 * 注意：导出功能已由前端 ExcelJS 接管（保证样式零丢失），后端不提供导出接口。
 */
@RestController
@RequestMapping("/api/excel")
public class ExcelFileController {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileController.class);

    @Autowired
    private ExcelParserService parserService;

    @Autowired
    private ExcelDocumentService documentService;

    @Autowired
    private ExcelSheetService sheetService;

    @Autowired
    private JsonHelper json;

    @Value("${excel.upload.path:./upload}")
    private String uploadPath;

    @PostMapping("/upload")
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename();
            String savedName = UUID.randomUUID().toString() + "_" + originalName;
            Path dir = Paths.get(uploadPath).toAbsolutePath().normalize();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path filePath = dir.resolve(savedName);
            file.transferTo(filePath.toFile());

            ExcelDocument doc = new ExcelDocument();
            doc.setName(originalName);
            doc.setFilePath(filePath.toString());
            doc.setFileSize(file.getSize());
            doc.setCreatorId("demo-user");
            documentService.create(doc);

            List<ExcelSheet> sheets = parserService.parseAndSave(
                    new FileInputStream(filePath.toFile()), doc);

            List<String> sheetNames = new ArrayList<>();
            for (ExcelSheet s : sheets) {
                sheetNames.add(s.getSheetName());
            }
            documentService.updateSheetMeta(doc.getId(), sheets.size(), json.toJson(sheetNames));

            List<Map<String, Object>> sheetInfoList = buildSheetInfoList(sheets);
            Map<String, Object> result = new HashMap<>();
            result.put("documentId", doc.getId());
            result.put("name", doc.getName());
            result.put("sheetCount", sheets.size());
            result.put("sheets", sheetInfoList);

            log.info("上传成功: {} → documentId={}, sheets={}", originalName, doc.getId(), sheets.size());
            return ApiResponse.ok("上传成功", result);

        } catch (Exception e) {
            log.error("上传失败", e);
            return ApiResponse.fail("上传失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> buildSheetInfoList(List<ExcelSheet> sheets) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExcelSheet s : sheets) {
            Map<String, Object> info = new HashMap<>();
            info.put("sheetId", s.getId());
            info.put("sheetIndex", s.getSheetIndex());
            info.put("sheetName", s.getSheetName());
            info.put("totalRows", s.getTotalRows());
            info.put("totalCols", s.getTotalCols());
            info.put("chunkCount", s.getChunkCount());
            info.put("active", s.getActive());
            list.add(info);
        }
        return list;
    }
}
