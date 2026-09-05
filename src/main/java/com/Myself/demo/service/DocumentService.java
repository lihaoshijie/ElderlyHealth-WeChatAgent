package com.Myself.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class DocumentService {
    public String extractText(byte[] bytes, String fileName) {
        String lower = fileName.toLowerCase();
        try {
            if (lower.endsWith(".pdf")) {
                var doc = Loader.loadPDF(bytes);
                var stripper = new PDFTextStripper();
                String text = stripper.getText(doc);
                doc.close();
                return mergeBrokenLines(text);
            }
            if (lower.endsWith(".docx")) {
                var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                var ex = new XWPFWordExtractor(doc);
                String text = ex.getText();
                ex.close();
                return text;
            }
            if (lower.endsWith(".xlsx")) {
                var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append("## Sheet: ").append(wb.getSheetName(i)).append("\n\n");
                    var sheet = wb.getSheetAt(i);
                    for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                        var row = sheet.getRow(r);
                        if (row != null) {
                            StringBuilder line = new StringBuilder();
                            for (var cell : row) {
                                String v = cell.toString().trim();
                                if (!v.isEmpty()) line.append(v).append(" | ");
                            }
                            if (line.length() > 0) {
                                sb.append(line.substring(0, line.length() - 3)).append("\n");
                            }
                        }
                    }
                }
                wb.close();
                return sb.toString();
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new String(bytes, "UTF-8");
            }
        } catch (Exception e) {
            log.error("提取文档失败: {}", fileName, e);
        }
        return null;
    }

    private String mergeBrokenLines(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) { sb.append("\n"); continue; }
            boolean endsWithPunct = line.matches(".*[。！？；：.!?;:]$");
            if (i < lines.length - 1) {
                String next = lines[i + 1].trim();
                if (!endsWithPunct && !next.isEmpty() && !next.matches("^\\d+[.、]") && !next.matches("^[一二三四五六七八九十]+[、.]")) {
                    sb.append(line);
                    continue;
                }
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }


    public List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        String[] lines = text.split("\\r?\\n");
        List<String> paragraphs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        java.util.regex.Pattern title = java.util.regex.Pattern.compile(
                "^\\s*(([一二三四五六七八九十]+)[、.])|(\\d+[、.])|(##?\\s*)");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (cur.length() > 0) { paragraphs.add(cur.toString().trim()); cur.setLength(0); }
                continue;
            }
            if (title.matcher(t).find()) {
                if (cur.length() > 0) { paragraphs.add(cur.toString().trim()); cur.setLength(0); }
                cur.append(t).append(" ");
            } else {
                cur.append(t).append(" ");
            }
        }
        if (cur.length() > 0) paragraphs.add(cur.toString().trim());
        if (paragraphs.isEmpty()) return chunks;

        List<String> result = new ArrayList<>();
        for (String p : paragraphs) {
            if (p.length() <= chunkSize) {
                result.add(p);
            } else {
                String[] sentences = p.split("(?<=[。！？.!?])");
                StringBuilder piece = new StringBuilder();
                for (String s : sentences) {
                    if (piece.length() + s.length() > chunkSize && piece.length() > 0) {
                        result.add(piece.toString().trim()); piece.setLength(0);
                    }
                    piece.append(s);
                }
                if (piece.length() > 0) result.add(piece.toString().trim());
            }
        }

        List<String> finalChunks = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            String c = result.get(i);
            if (i > 0 && overlap > 0) {
                String prev = result.get(i - 1);
                String tail = prev.length() > overlap ? prev.substring(prev.length() - overlap) : prev;
                c = tail + c;
            }
            finalChunks.add(c);
        }
        return finalChunks;
    }
}