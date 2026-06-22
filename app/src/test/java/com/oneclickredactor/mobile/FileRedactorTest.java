package com.oneclickredactor.mobile;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileRedactorTest {
    @Test
    public void processesUtf8TextAndWritesBom() throws Exception {
        byte[] source = "手机13800138000".getBytes(StandardCharsets.UTF_8);
        FileRedactor.Result result = FileRedactor.process("样本.txt", source,
                new RedactionEngine(List.of(), true));

        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                new byte[]{result.outputBytes[0], result.outputBytes[1], result.outputBytes[2]});
        String changed = new String(result.outputBytes, 3, result.outputBytes.length - 3, StandardCharsets.UTF_8);
        assertEquals("手机138****8000", changed);
        assertArrayEquals("手机13800138000".getBytes(StandardCharsets.UTF_8), source);
    }

    @Test
    public void processesTextSplitAcrossDocxRuns() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<w:document xmlns:w=\"urn:test\"><w:body><w:p>" +
                "<w:r><w:t>手机13800</w:t></w:r><w:r><w:t>138000</w:t></w:r>" +
                "</w:p></w:body></w:document>";
        byte[] source = oneEntryZip("word/document.xml", xml);

        FileRedactor.Result result = FileRedactor.process("材料.docx", source,
                new RedactionEngine(List.of(), true));
        String changedXml = readZipEntry(result.outputBytes, "word/document.xml");

        assertTrue(changedXml.contains("138****8000"));
        assertFalse(changedXml.contains("13800138000"));
        assertTrue(result.detailedReport().contains("手机号 1"));
    }

    @Test
    public void skipsGeneratedArtifactsAndSupportsDesktopFormats() {
        assertTrue(FileRedactor.isGeneratedArtifact("材料_已脱敏.docx"));
        assertTrue(FileRedactor.isGeneratedArtifact("最近一次脱敏报告.txt"));
        for (String name : new String[]{"a.doc", "a.docx", "a.docm", "a.xlsx", "a.xlsm", "a.pptx", "a.pptm", "a.txt", "a.csv"}) {
            assertTrue(name, FileRedactor.isSupported(name));
        }
        assertFalse(FileRedactor.isSupported("a.xls"));
    }

    private static byte[] oneEntryZip(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static String readZipEntry(byte[] bytes, String expected) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals(expected)) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = zip.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
        throw new AssertionError("missing entry " + expected);
    }
}
