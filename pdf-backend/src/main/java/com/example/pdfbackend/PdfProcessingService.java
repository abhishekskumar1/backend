package com.example.pdfbackend;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PdfProcessingService {

    // ==========================================
    // 1. PAGES PER SHEET & FOLDABLE LOGIC
    // ==========================================
    public byte[] generatePagesPerSheet(byte[] inputBytes, int pagesPerSheet, String paperSize, String mode) throws Exception {

        PDRectangle outSize = switch (paperSize.toUpperCase(Locale.ROOT)) {
            case "A3" -> PDRectangle.A3;
            case "LETTER" -> PDRectangle.LETTER;
            case "LEGAL" -> PDRectangle.LEGAL;
            default -> PDRectangle.A4;
        };

        boolean foldable = mode != null && mode.toLowerCase(Locale.ROOT).startsWith("fold");

        try (PDDocument src = PDDocument.load(inputBytes);
             PDDocument dest = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int srcCount = src.getNumberOfPages();
            LayerUtility layer = new LayerUtility(dest);

            if (!foldable) {
                // ===== STANDARD: sequential pages 1..n =====
                int totalSheets = (int) Math.ceil(srcCount / (double) pagesPerSheet);
                for (int i = 0; i < totalSheets; i++) {
                    PDPage outPage = new PDPage(outSize);
                    dest.addPage(outPage);

                    try (PDPageContentStream cs = new PDPageContentStream(dest, outPage)) {
                        for (int j = 0; j < pagesPerSheet; j++) {
                            int srcIndex1 = (i * pagesPerSheet) + j + 1;
                            if (srcIndex1 <= srcCount) {
                                drawOne(layer, dest, src, cs, srcIndex1 - 1, j, pagesPerSheet, outSize);
                            }
                        }
                    }
                }
            } else {
                // ===== FOLDABLE BOOKLET LOGIC =====
                int totalSheets = (int) Math.ceil(srcCount / (double) (pagesPerSheet * 2));

                // 1) Fronts
                for (int i = 0; i < totalSheets; i++) {
                    PDPage outPage = new PDPage(outSize);
                    dest.addPage(outPage);

                    int batchStart0 = i * (pagesPerSheet * 2);

                    try (PDPageContentStream cs = new PDPageContentStream(dest, outPage)) {
                        for (int j = 0; j < pagesPerSheet; j++) {
                            int srcIndex1 = batchStart0 + (j * 2) + 1;
                            if (srcIndex1 <= srcCount) {
                                drawOne(layer, dest, src, cs, srcIndex1 - 1, j, pagesPerSheet, outSize);
                            }
                        }
                    }
                }

                // 2) Backs
                int cols = gridCols(pagesPerSheet);
                for (int i = 0; i < totalSheets; i++) {
                    PDPage outPage = new PDPage(outSize);
                    dest.addPage(outPage);

                    int batchStart0 = i * (pagesPerSheet * 2);

                    try (PDPageContentStream cs = new PDPageContentStream(dest, outPage)) {
                        for (int j = 0; j < pagesPerSheet; j++) {
                            int row = j / cols;
                            int col = j % cols;
                            int reversedCol = cols - 1 - col;
                            int reversedPos = row * cols + reversedCol;

                            int srcIndex1 = batchStart0 + (reversedPos * 2) + 2;
                            if (srcIndex1 <= srcCount) {
                                drawOne(layer, dest, src, cs, srcIndex1 - 1, j, pagesPerSheet, outSize);
                            }
                        }
                    }
                }
            }

            dest.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 2. MERGE PDF (Robust Multi-file merge)
    // ==========================================
    public byte[] mergePdfs(MultipartFile[] files) throws Exception {
        try (PDDocument dest = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    try (PDDocument src = PDDocument.load(file.getBytes())) {
                        for (int i = 0; i < src.getNumberOfPages(); i++) {
                            dest.importPage(src.getPage(i));
                        }
                    }
                }
            }
            dest.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 3. SPLIT / CUT PDF (Extracts requested page ranges)
    // ==========================================
    public byte[] splitPdf(byte[] inputBytes, String pagesStr) throws Exception {
        try (PDDocument src = PDDocument.load(inputBytes);
             PDDocument dest = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int total = src.getNumberOfPages();
            List<Integer> pagesToKeep = parsePageListOrRange(pagesStr, total);
            for (int p : pagesToKeep) {
                if (p >= 1 && p <= total) {
                    dest.importPage(src.getPage(p - 1));
                }
            }
            dest.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 4. COMPRESS PDF
    // ==========================================
    public byte[] compressPdf(byte[] inputBytes, String level) throws Exception {
        try (PDDocument doc = PDDocument.load(inputBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 5. DELETE PAGES (Deletes specified pages or ranges)
    // ==========================================
    public byte[] deletePages(byte[] inputBytes, String pagesStr) throws Exception {
        try (PDDocument src = PDDocument.load(inputBytes);
             PDDocument dest = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int totalPages = src.getNumberOfPages();
            Set<Integer> pagesToDelete = new HashSet<>(parsePageListOrRange(pagesStr, totalPages));

            for (int i = 1; i <= totalPages; i++) {
                if (!pagesToDelete.contains(i)) {
                    dest.importPage(src.getPage(i - 1));
                }
            }

            dest.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 6. REARRANGE PAGES (Sequences pages by custom order)
    // ==========================================
    public byte[] rearrangePages(byte[] inputBytes, String order) throws Exception {
        try (PDDocument src = PDDocument.load(inputBytes);
             PDDocument dest = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int total = src.getNumberOfPages();
            if (order != null && !order.isBlank()) {
                String[] tokens = order.replaceAll("\\s+", "").split(",");
                for (String token : tokens) {
                    try {
                        int pageNum = Integer.parseInt(token.trim());
                        if (pageNum >= 1 && pageNum <= total) {
                            dest.importPage(src.getPage(pageNum - 1));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            dest.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // 7. ADD PAGE NUMBERS (Fully Working)
    // ==========================================
    public byte[] addPageNumbers(byte[] inputBytes, String position, String margin, int startNumber, String format) throws Exception {
        try (PDDocument doc = PDDocument.load(inputBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            org.apache.pdfbox.pdmodel.font.PDFont font = org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD;
            float fontSize = 12.0f;
            float marginOffset = margin.equals("tight") ? 15f : (margin.equals("wide") ? 50f : 30f);

            int totalPages = doc.getNumberOfPages();
            int currentNumber = startNumber;

            for (int i = 0; i < totalPages; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle mediaBox = page.getMediaBox();

                String text = String.valueOf(currentNumber);
                if (format.equals("pageX")) text = "Page " + currentNumber;
                if (format.equals("xOfY")) text = currentNumber + " of " + (startNumber + totalPages - 1);

                float textWidth = font.getStringWidth(text) / 1000 * fontSize;

                float x = mediaBox.getWidth() - marginOffset - textWidth; // Default to Right
                if (position.toLowerCase().contains("left")) {
                    x = marginOffset;
                } else if (position.toLowerCase().contains("center")) {
                    x = (mediaBox.getWidth() - textWidth) / 2f;
                }

                float y = marginOffset; // Default to Bottom
                if (position.toLowerCase().contains("top")) {
                    y = mediaBox.getHeight() - marginOffset - fontSize;
                }

                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.newLineAtOffset(x, y);
                    cs.showText(text);
                    cs.endText();
                }
                currentNumber++;
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================
    private List<Integer> parsePageListOrRange(String pagesStr, int totalPages) {
        List<Integer> result = new ArrayList<>();
        if (pagesStr == null || pagesStr.isBlank()) {
            for (int i = 1; i <= totalPages; i++) result.add(i);
            return result;
        }
        String cleaned = pagesStr.replaceAll("\\s+", "");
        String[] tokens = cleaned.split(",");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (token.contains("-")) {
                String[] parts = token.split("-");
                if (parts.length == 2) {
                    try {
                        int start = Integer.parseInt(parts[0]);
                        int end = Integer.parseInt(parts[1]);
                        if (start <= end) {
                            for (int i = start; i <= end; i++) {
                                if (i >= 1 && i <= totalPages) result.add(i);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                try {
                    int p = Integer.parseInt(token);
                    if (p >= 1 && p <= totalPages) result.add(p);
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private int gridCols(int pagesPerSheet) {
        return switch (pagesPerSheet) {
            case 2 -> 1;
            case 4 -> 2;
            case 8 -> 2;
            case 16 -> 4;
            default -> 2;
        };
    }

    private int gridRows(int pagesPerSheet) {
        return switch (pagesPerSheet) {
            case 2 -> 2;
            case 4 -> 2;
            case 8 -> 4;
            case 16 -> 4;
            default -> 2;
        };
    }

    private void drawOne(LayerUtility layer, PDDocument dest, PDDocument src, PDPageContentStream cs,
                         int srcPageIndex0, int positionIndex, int pagesPerSheet, PDRectangle outSize) throws Exception {

        if (srcPageIndex0 < 0 || srcPageIndex0 >= src.getNumberOfPages()) return;

        int cols = gridCols(pagesPerSheet);
        int rows = gridRows(pagesPerSheet);

        float cellW = outSize.getWidth() / cols;
        float cellH = outSize.getHeight() / rows;

        int row = positionIndex / cols;
        int col = positionIndex % cols;

        float cellX = col * cellW;
        float cellY = outSize.getHeight() - ((row + 1) * cellH);

        PDPage srcPage = src.getPage(srcPageIndex0);
        PDRectangle srcBox = (srcPage.getCropBox() != null) ? srcPage.getCropBox() : srcPage.getMediaBox();

        float srcW = srcBox.getWidth();
        float srcH = srcBox.getHeight();
        float scale = Math.min(cellW / srcW, cellH / srcH);

        float drawW = srcW * scale;
        float drawH = srcH * scale;

        float offsetX = cellX + (cellW - drawW) / 2f;
        float offsetY = cellY + (cellH - drawH) / 2f;

        PDFormXObject form = layer.importPageAsForm(src, srcPageIndex0);

        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(offsetX, offsetY));
        cs.transform(Matrix.getScaleInstance(scale, scale));
        cs.drawForm(form);
        cs.restoreGraphicsState();
    }
}
