package com.oneclickredactor.mobile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidRuntimeTest {
    @Test
    public void redactionEngineRunsOnAndroidRuntime() {
        RedactionEngine engine = new RedactionEngine(List.of(), true);
        RedactionEngine.Stats stats = new RedactionEngine.Stats();
        String output = engine.anonymize("姓名：张三，手机13800138000，地址：北京市海淀区中关村大街18号。", stats);
        assertTrue(output.contains("张某"));
        assertTrue(output.contains("138****8000"));
        assertTrue(output.contains("[地址已脱敏]"));
    }

    @Test
    public void openXmlProcessingRunsOnAndroidRuntime() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<w:document xmlns:w=\"urn:test\"><w:body><w:p><w:r>" +
                "<w:t>邮箱zhangsan@example.com</w:t></w:r></w:p></w:body></w:document>";
        ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(packageBytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        FileRedactor.Result result = FileRedactor.process("材料.docx", packageBytes.toByteArray(),
                new RedactionEngine(List.of(), true));
        String changed = readEntry(result.outputBytes, "word/document.xml");
        assertTrue(changed + " notes=" + result.notes, changed.contains("z***@example.com"));
        assertFalse(changed.contains("zhangsan@example.com"));
    }

    @Test
    public void legacyDocParsingAndFixedLengthPatchRunOnAndroidRuntime() throws Exception {
        byte[] source;
        try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("legacy-test.doc")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) >= 0) if (read > 0) buffer.write(chunk, 0, read);
            source = buffer.toByteArray();
        }
        List<RedactionEngine.CustomRule> rules = List.of(new RedactionEngine.CustomRule("Ryan", "R***"));
        FileRedactor.Result result = FileRedactor.process("legacy-test.doc", source,
                new RedactionEngine(rules, false));

        assertTrue(result.stats.total() > 0);
        assertTrue(result.notes.toString(), result.notes.toString().contains("定长兼容脱敏模式"));
        assertTrue(result.outputBytes.length == source.length);
    }

    private static String readEntry(byte[] bytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals(name)) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = zip.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
        throw new AssertionError("missing entry " + name);
    }
}
