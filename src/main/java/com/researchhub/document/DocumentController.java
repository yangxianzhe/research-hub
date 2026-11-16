package com.researchhub.document;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/documents")
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) { this.documents = documents; }

    public record TextImport(@NotBlank @Size(max = 240) String title,
                             @NotBlank @Size(max = 1_000_000) String content, DocumentService.Visibility visibility) { }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentService.ImportResult text(@PathVariable String workspaceId, @Valid @RequestBody TextImport body,
                                             HttpServletRequest request) {
        return documents.upload(workspaceId, AuthInterceptor.userId(request), body.title(), "text-import.txt", "text/plain",
                body.content().getBytes(StandardCharsets.UTF_8), body.visibility());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentService.ImportResult upload(@PathVariable String workspaceId, @RequestParam String title,
                                               @RequestParam(defaultValue = "PRIVATE") DocumentService.Visibility visibility,
                                               @RequestPart MultipartFile file, HttpServletRequest request) throws IOException {
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.length() > 255) throw ApiException.badRequest("文件名最多为 255 字符");
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf") && !lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw ApiException.badRequest("第一版支持 PDF、Markdown 和 UTF-8 文本文件");
        }
        byte[] bytes = file.getBytes();
        boolean pdf = bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        if (lower.endsWith(".pdf") != pdf) throw ApiException.badRequest("文件扩展名与 PDF 文件内容不匹配");
        return documents.upload(workspaceId, AuthInterceptor.userId(request), title, filename,
                pdf ? "application/pdf" : "text/plain", bytes, visibility);
    }

    @GetMapping
    public List<DocumentService.Document> list(@PathVariable String workspaceId, HttpServletRequest request) {
        return documents.list(workspaceId, AuthInterceptor.userId(request));
    }

    @GetMapping("/{id}")
    public DocumentService.Document get(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) {
        return documents.get(workspaceId, id, AuthInterceptor.userId(request));
    }

    @GetMapping("/{id}/chunks")
    public List<DocumentService.Chunk> chunks(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) {
        return documents.chunks(workspaceId, id, AuthInterceptor.userId(request));
    }
}
