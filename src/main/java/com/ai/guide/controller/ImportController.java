package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.service.ScenicDataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文件导入接口
 * <p>
 * 上传 Word / Excel / PDF 等文档，自动解析并向量化存入 Milvus
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ImportController {

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    /** 统一导入接口，返回标准 Result 格式 */
    @PostMapping("/import")
    public Result<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "上传失败：文件为空");
        }

        try {
            scenicDataImportService.importUniversalDocument(file);
            String fileName = file.getOriginalFilename();
            return Result.success("【" + fileName + "】导入成功", fileName);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(500, "导入失败：" + e.getMessage());
        }
    }
}
