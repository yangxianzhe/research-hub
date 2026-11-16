package com.researchhub.document;

import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentParser {
    public record Parsed(int pageCount, List<DocumentService.Chunk> chunks) { }
    private final JdbcTemplate db;

    public DocumentParser(JdbcTemplate db) { this.db = db; }

    public Parsed parse(String documentId) {
        byte[] bytes = db.queryForObject("SELECT source_bytes FROM documents WHERE id=?", byte[].class, documentId);
        String mediaType = db.queryForObject("SELECT media_type FROM documents WHERE id=?", String.class, documentId);
        if (bytes == null) throw ApiException.notFound();
        List<DocumentService.Chunk> chunks = new ArrayList<>();
        int pages = 1;
        if ("application/pdf".equals(mediaType)) {
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted()) throw ApiException.badRequest("第一版不支持加密 PDF，请先解密");
                pages = pdf.getNumberOfPages();
                if (pages == 0 || pages > 150) throw ApiException.badRequest("PDF 应为 1 到 150 页");
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int page = 1; page <= pages; page++) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("解析已中断");
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    split(stripper.getText(pdf), page, chunks);
                }
            } catch (java.io.IOException e) {
                throw ApiException.badRequest("PDF 无法读取，请检查文件是否损坏或需要密码");
            }
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                split(text.replace("\uFEFF", ""), 1, chunks);
            } catch (CharacterCodingException e) {
                throw ApiException.badRequest("文本文件必须使用 UTF-8 编码");
            }
        }
        if (chunks.isEmpty()) throw ApiException.badRequest("未提取到正文。扫描版 PDF 需要先进行 OCR");
        return new Parsed(pages, List.copyOf(chunks));
    }

    public void persist(String documentId, Parsed parsed) {
        db.update("DELETE FROM document_chunks WHERE document_id=?", documentId);
        for (var chunk : parsed.chunks()) {
            db.update("INSERT INTO document_chunks(id,document_id,page_number,chunk_index,content) VALUES (?,?,?,?,?)",
                    Values.id(), documentId, chunk.pageNumber(), chunk.chunkIndex(), chunk.content());
        }
        db.update("UPDATE documents SET status='READY',error_message=NULL,page_count=? WHERE id=?", parsed.pageCount(), documentId);
    }

    private static void split(String input, int page, List<DocumentService.Chunk> chunks) {
        String text = input.replace("\u0000", "").strip();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 1200);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String content = text.substring(start, end).strip();
            if (!content.isBlank()) chunks.add(new DocumentService.Chunk(page, chunks.size(), content));
            if (chunks.size() > 2000) throw ApiException.badRequest("正文过大，第一版最多支持 2000 个文本片段，请拆分文档");
            if (end == text.length()) break;
            start = end - 150;
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
    }
}
