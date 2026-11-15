package com.example.pdfbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class PdfProcessingServiceTest {

    private PdfProcessingService service;
    private byte[] samplePdfBytes;

    @BeforeEach
    void setUp() throws IOException {
        service = new PdfProcessingService();

        // Create a 2-page sample PDF
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(100, 700);
                cs.showText("Page 1 Test Content");
                cs.endText();
            }

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(100, 700);
                cs.showText("Page 2 Test Content");
                cs.endText();
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                samplePdfBytes = out.toByteArray();
            }
        }
    }

    @Test
    void testGeneratePagesPerSheet() throws Exception {
        byte[] result = service.generatePagesPerSheet(samplePdfBytes, 2, "portrait", "standard");
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void testMergePdfs() throws Exception {
        MultipartFile f1 = new MockMultipartFile("files", "doc1.pdf", "application/pdf", samplePdfBytes);
        MultipartFile f2 = new MockMultipartFile("files", "doc2.pdf", "application/pdf", samplePdfBytes);

        byte[] result = service.mergePdfs(new MultipartFile[]{f1, f2});
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(4, doc.getNumberOfPages());
        }
    }

    @Test
    void testSplitPdf() throws Exception {
        byte[] result = service.splitPdf(samplePdfBytes, "1");
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void testCompressPdf() throws Exception {
        byte[] result = service.compressPdf(samplePdfBytes, "recommended");
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void testAddPageNumbers() throws Exception {
        byte[] result = service.addPageNumbers(samplePdfBytes, "bottom-center", "normal", 1, "numeric");
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    @Test
    void testRearrangePages() throws Exception {
        byte[] result = service.rearrangePages(samplePdfBytes, "2,1");
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    @Test
    void testDeletePages() throws Exception {
        byte[] result = service.deletePages(samplePdfBytes, "2");
        assertNotNull(result);
        assertTrue(result.length > 0);
        try (PDDocument doc = PDDocument.load(result)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }
}
