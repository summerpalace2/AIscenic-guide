package com.ai.guide.controller;

import com.ai.guide.service.ScenicDataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ImportController {

    // 注入我们刚才定义的万能 Service
    @Autowired
    private ScenicDataImportService scenicDataImportService;

    /**
     * 统一导入接口：支持 Word, Excel, PDF 等
     */
    @PostMapping("/import")
    public String uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return "上传失败：文件为空！";
        }

        try {
            // 调用 Service 层的万能导入方法
            scenicDataImportService.importUniversalDocument(file);

            String fileName = file.getOriginalFilename();
            return "【" + fileName + "】导入成功！AI 已将其向量化并存入知识库。";
        } catch (IOException e) {
            e.printStackTrace();
            return "文件解析失败：" + e.getMessage();
        } catch (Exception e) {
            e.printStackTrace();
            return "系统错误：" + e.getMessage();
        }
    }
}