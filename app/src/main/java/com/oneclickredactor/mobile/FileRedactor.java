package com.oneclickredactor.mobile;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Offline document processor. It never mutates source bytes. */
public final class FileRedactor {
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            ".doc", ".docx", ".docm", ".xlsx", ".xlsm", ".pptx", ".pptm", ".txt", ".csv"));

    private FileRedactor() {}

    public static boolean isSupported(String name) {
        return SUPPORTED.contains(extension(name));
    }

    public static boolean isGeneratedArtifact(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.contains("_已脱敏")
                || lower.endsWith(".脱敏报告.txt")
                || lower.equals("最近一次脱敏报告.txt")
                || lower.equals("命令行脱敏报告.txt")
                || lower.equals("命令行用法.txt");
    }

    public static String outputName(String sourceName) {
        int dot = sourceName.lastIndexOf('.');
        if (dot <= 0) return sourceName + "_已脱敏";
        return sourceName.substring(0, dot) + "_已脱敏" + sourceName.substring(dot);
    }

    public static String uniqueOutputName(String sourceName, NameExists exists) {
        String preferred = outputName(sourceName);
        if (!exists.test(preferred)) return preferred;
        int dot = sourceName.lastIndexOf('.');
        String stem = dot <= 0 ? sourceName : sourceName.substring(0, dot);
        String ext = dot <= 0 ? "" : sourceName.substring(dot);
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        for (int sequence = 0; sequence < 1000; sequence++) {
            String suffix = sequence == 0 ? stamp : stamp + "_" + sequence;
            String candidate = stem + "_已脱敏_" + suffix + ext;
            if (!exists.test(candidate)) return candidate;
        }
        return stem + "_已脱敏_" + UUID.randomUUID().toString().replace("-", "") + ext;
    }

    public static Result process(String sourceName, byte[] source, RedactionEngine engine) throws Exception {
        if (!isSupported(sourceName)) throw new IOException("不支持该文件格式：" + sourceName);
        Result result = new Result(sourceName, outputName(sourceName));
        String ext = extension(sourceName);
        if (ext.equals(".txt") || ext.equals(".csv")) {
            result.outputBytes = processText(source, engine, result.stats);
        } else if (ext.equals(".doc")) {
            result.outputBytes = processBinaryWord(source, engine, result);
        } else {
            result.outputBytes = processOpenXml(source, ext, engine, result);
        }
        result.success = true;
        return result;
    }

    private static byte[] processText(byte[] source, RedactionEngine engine, RedactionEngine.Stats stats) {
        Charset charset = detectEncoding(source);
        String text = charset.decode(ByteBuffer.wrap(source)).toString();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        String changed = engine.anonymize(text, stats);
        byte[] utf8 = changed.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[utf8.length + 3];
        output[0] = (byte) 0xEF;
        output[1] = (byte) 0xBB;
        output[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, output, 3, utf8.length);
        return output;
    }

    private static Charset detectEncoding(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) return StandardCharsets.UTF_8;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) return StandardCharsets.UTF_16LE;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) return StandardCharsets.UTF_16BE;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException ignored) {
            try {
                return Charset.forName("GB18030");
            } catch (Exception unavailable) {
                return Charset.defaultCharset();
            }
        }
    }

    private static byte[] processBinaryWord(byte[] source, RedactionEngine engine, Result result) throws Exception {
        List<BinaryEdit> edits = new ArrayList<>();
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(source))) {
            List<Range> ranges = getWordRanges(document);
            for (Range range : ranges) learnNames(range, engine);
            Set<String> seen = new HashSet<>();
            for (Range range : ranges) {
                for (int i = 0; i < range.numParagraphs(); i++) {
                    Paragraph paragraph = range.getParagraph(i);
                    String original = trimWordControls(paragraph.text());
                    if (original == null || original.isEmpty()) continue;
                    String key = paragraph.getStartOffset() + ":" + original.length();
                    if (!seen.add(key)) continue;
                    String changed = engine.anonymizeWithoutTextGrowth(original, result.stats);
                    if (!original.equals(changed)) edits.add(new BinaryEdit(original, changed));
                }
            }
        } catch (Exception ex) {
            throw new IOException("无法读取该 .doc 文件。文件可能已加密、损坏，或并非 Word 97-2003 二进制格式。", ex);
        }

        if (edits.isEmpty()) return Arrays.copyOf(source, source.length);
        result.notes.add("旧版 .doc 使用定长兼容脱敏模式；原二进制结构保持不变，较短替换会使用星号补齐。");
        byte[] patched = Arrays.copyOf(source, source.length);
        Set<String> processed = new HashSet<>();
        for (BinaryEdit edit : edits) {
            String key = edit.original + '\u0000' + edit.written;
            if (!processed.add(key)) continue;
            if (replaceBinaryWordText(patched, edit.original, edit.written) == 0) {
                throw new IOException("无法在 .doc 二进制文字流中定位待脱敏段落，已停止写出以避免损坏文件。");
            }
        }
        verifyBinaryWord(patched, edits);
        return patched;
    }

    private static List<Range> getWordRanges(HWPFDocument document) {
        List<Range> ranges = new ArrayList<>();
        addRange(ranges, safeRange(document::getRange));
        addRange(ranges, safeRange(document::getHeaderStoryRange));
        addRange(ranges, safeRange(document::getFootnoteRange));
        addRange(ranges, safeRange(document::getEndnoteRange));
        addRange(ranges, safeRange(document::getCommentsRange));
        addRange(ranges, safeRange(document::getMainTextboxRange));
        return ranges;
    }

    private static Range safeRange(RangeFactory factory) {
        try { return factory.get(); } catch (Throwable ignored) { return null; }
    }

    private static void addRange(List<Range> ranges, Range range) {
        if (range != null && range.getEndOffset() > range.getStartOffset()) ranges.add(range);
    }

    private static void learnNames(Range range, RedactionEngine engine) {
        for (int i = 0; i < range.numParagraphs(); i++) engine.learnNamesFromText(range.getParagraph(i).text());
    }

    private static String trimWordControls(String text) {
        if (text == null || text.isEmpty()) return text;
        int length = text.length();
        while (length > 0) {
            char c = text.charAt(length - 1);
            if (c != '\r' && c != '\n' && c != '\u0007') break;
            length--;
        }
        return length == text.length() ? text : text.substring(0, length);
    }

    private static int replaceBinaryWordText(byte[] document, String original, String replacement) {
        Charset[] charsets = {StandardCharsets.UTF_16LE, Charset.forName("GBK"), Charset.forName("windows-1252")};
        int replacements = 0;
        for (Charset charset : charsets) {
            try {
                byte[] oldBytes = strictEncode(charset, original);
                byte[] newBytes = strictEncode(charset, replacement);
                if (oldBytes.length > 0 && oldBytes.length == newBytes.length) replacements += replaceAll(document, oldBytes, newBytes);
            } catch (CharacterCodingException ignored) {
                // This paragraph cannot be represented in the current legacy encoding.
            }
        }
        return replacements;
    }

    private static byte[] strictEncode(Charset charset, String text) throws CharacterCodingException {
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static int replaceAll(byte[] source, byte[] original, byte[] replacement) {
        int replacements = 0;
        for (int offset = 0; offset <= source.length - original.length;) {
            boolean matches = true;
            for (int i = 0; i < original.length; i++) {
                if (source[offset + i] != original[i]) { matches = false; break; }
            }
            if (!matches) { offset++; continue; }
            System.arraycopy(replacement, 0, source, offset, replacement.length);
            replacements++;
            offset += original.length;
        }
        return replacements;
    }

    private static void verifyBinaryWord(byte[] output, List<BinaryEdit> edits) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(output))) {
            Range range = document.getOverallRange();
            String text = range == null ? null : range.text();
            if (text == null || text.isEmpty()) throw new IOException("写出后的 .doc 文档无法读取正文。");
            for (BinaryEdit edit : edits) {
                if (!edit.written.isEmpty() && !text.contains(edit.written)) throw new IOException("写出后的 .doc 文档未通过脱敏内容校验。");
            }
        }
    }

    private static byte[] processOpenXml(byte[] source, String extension, RedactionEngine engine, Result result) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(source.length, 4096));
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry destination = new ZipEntry(entry.getName());
                destination.setTime(entry.getTime());
                if (entry.getComment() != null) destination.setComment(entry.getComment());
                if (entry.getExtra() != null) destination.setExtra(entry.getExtra());
                zip.putNextEntry(destination);
                if (!entry.isDirectory()) {
                    byte[] content = readAll(input);
                    if (shouldProcessXmlEntry(entry.getName(), extension)) {
                        try {
                            content = processXml(content, extension, engine, result.stats);
                        } catch (Exception ex) {
                            result.notes.add("XML 片段未能处理，已原样保留：" + entry.getName() + "；" + safeMessage(ex));
                        }
                    }
                    zip.write(content);
                }
                zip.closeEntry();
                input.closeEntry();
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("无法处理 Office Open XML 文件。", ex);
        }
        return output.toByteArray();
    }

    private static boolean shouldProcessXmlEntry(String entryName, String extension) {
        String name = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xml")) return false;
        if (extension.equals(".docx") || extension.equals(".docm")) return name.startsWith("word/") || name.startsWith("docprops/");
        if (extension.equals(".xlsx") || extension.equals(".xlsm")) return name.startsWith("xl/") || name.startsWith("docprops/");
        if (extension.equals(".pptx") || extension.equals(".pptm")) return name.startsWith("ppt/") || name.startsWith("docprops/");
        return false;
    }

    private static byte[] processXml(byte[] xml, String extension, RedactionEngine engine, RedactionEngine.Stats stats) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Android's built-in parser reports XInclude as an unknown specification.
        // Keep the secure setting where supported without rejecting valid Office XML.
        try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
        try { factory.setExpandEntityReferences(false); } catch (UnsupportedOperationException ignored) {}
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        boolean wordOrPresentation = extension.equals(".docx") || extension.equals(".docm") || extension.equals(".pptx") || extension.equals(".pptm");
        boolean spreadsheet = extension.equals(".xlsx") || extension.equals(".xlsm");
        if (wordOrPresentation) learnNames(document, "p", engine);
        if (spreadsheet) learnNames(document, "si", engine);

        Set<Node> processed = new HashSet<>();
        if (wordOrPresentation) {
            processTextContainers(document, "p", engine, stats, processed);
            processTextNodes(document, engine, stats, processed);
            processLeafElements(document, engine, stats, processed, false);
        } else if (spreadsheet) {
            processTextContainers(document, "si", engine, stats, processed);
            processTextContainers(document, "is", engine, stats, processed);
            processTextContainers(document, "text", engine, stats, processed);
            processTextNodes(document, engine, stats, processed);
            processWorksheetValues(document, engine, stats);
            processLeafElements(document, engine, stats, processed, true);
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        try { transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        if (!startsWithXmlDeclaration(xml)) transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream(xml.length + 256);
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private static void learnNames(Document document, String containerName, RedactionEngine engine) {
        for (Element container : elements(document, containerName)) {
            StringBuilder text = new StringBuilder();
            for (Element node : descendants(container, "t")) text.append(node.getTextContent());
            engine.learnNamesFromText(text.toString());
        }
    }

    private static void processTextContainers(Document document, String name, RedactionEngine engine, RedactionEngine.Stats stats, Set<Node> processed) {
        for (Element container : elements(document, name)) {
            List<Element> textNodes = descendants(container, "t");
            if (textNodes.isEmpty()) continue;
            StringBuilder joined = new StringBuilder();
            for (Element node : textNodes) joined.append(node.getTextContent());
            String original = joined.toString();
            String changed = engine.anonymize(original, stats);
            if (!original.equals(changed)) {
                textNodes.get(0).setTextContent(changed);
                preserveSpace(textNodes.get(0));
                for (int i = 1; i < textNodes.size(); i++) textNodes.get(i).setTextContent("");
            }
            processed.addAll(textNodes);
        }
    }

    private static void processTextNodes(Document document, RedactionEngine engine, RedactionEngine.Stats stats, Set<Node> processed) {
        for (Element element : elements(document, "t")) {
            if (processed.contains(element)) continue;
            String original = element.getTextContent();
            String changed = engine.anonymize(original, stats);
            if (!original.equals(changed)) { element.setTextContent(changed); preserveSpace(element); }
            processed.add(element);
        }
    }

    private static void processLeafElements(Document document, RedactionEngine engine, RedactionEngine.Stats stats, Set<Node> processed, boolean skipSpreadsheetValues) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element)) continue;
            Element element = (Element) all.item(i);
            if (processed.contains(element) || hasElementChildren(element)) continue;
            String name = localName(element);
            String original = element.getTextContent();
            if (original == null || original.isEmpty() || name.equals("t")) continue;
            if (skipSpreadsheetValues && (name.equals("v") || name.equals("f"))) continue;
            String changed = engine.anonymize(original, stats);
            if (!original.equals(changed)) { element.setTextContent(changed); preserveSpace(element); }
        }
    }

    private static void processWorksheetValues(Document document, RedactionEngine engine, RedactionEngine.Stats stats) {
        for (Element cell : elements(document, "c")) {
            String type = cell.getAttribute("t");
            if (type.equalsIgnoreCase("s") || type.equalsIgnoreCase("b")) continue;
            Element value = directChild(cell, "v");
            if (value == null || value.getTextContent().isEmpty()) continue;
            String original = value.getTextContent();
            String changed = engine.anonymizeStrongIdentifiersOnly(original, stats);
            if (!original.equals(changed)) { value.setTextContent(changed); cell.setAttribute("t", "str"); }
        }
    }

    private static List<Element> elements(Document document, String localName) {
        List<Element> values = new ArrayList<>();
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) values.add((Element) nodes.item(i));
        return values;
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> values = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) values.add((Element) nodes.item(i));
        return values;
    }

    private static Element directChild(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && localName((Element) node).equals(localName)) return (Element) node;
        }
        return null;
    }

    private static boolean hasElementChildren(Element element) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element) return true;
        return false;
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static void preserveSpace(Element element) {
        element.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) {}
    }

    private static boolean startsWithXmlDeclaration(byte[] bytes) {
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        while (offset < bytes.length && Character.isWhitespace((char) bytes[offset])) offset++;
        return offset + 5 <= bytes.length && new String(bytes, offset, 5, StandardCharsets.US_ASCII).equals("<?xml");
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String normalizeBinaryReplacement(String original, String changed) {
        if (changed == null) changed = "";
        if (changed.length() > original.length()) return changed.substring(0, original.length());
        if (changed.length() == original.length()) return changed;
        String padding = "*".repeat(original.length() - changed.length());
        String punctuation = "。；，、,.!?！？;：:";
        if (!changed.isEmpty() && punctuation.indexOf(changed.charAt(changed.length() - 1)) >= 0) {
            return changed.substring(0, changed.length() - 1) + padding + changed.charAt(changed.length() - 1);
        }
        return changed + padding;
    }

    private interface RangeFactory { Range get() throws Exception; }
    public interface NameExists { boolean test(String name); }

    private static final class BinaryEdit {
        final String original;
        final String written;
        BinaryEdit(String original, String changed) {
            this.original = original;
            this.written = normalizeBinaryReplacement(original, changed);
        }
    }

    public static final class Result {
        public final String sourceName;
        public String outputName;
        public byte[] outputBytes;
        public boolean success;
        public String message = "";
        public final RedactionEngine.Stats stats = new RedactionEngine.Stats();
        public final List<String> notes = new ArrayList<>();

        Result(String sourceName, String outputName) {
            this.sourceName = sourceName;
            this.outputName = outputName;
        }

        public String detailedReport() {
            StringBuilder report = new StringBuilder();
            report.append("原文件：").append(sourceName).append('\n');
            report.append("输出文件：").append(outputName == null ? "" : outputName).append('\n');
            report.append("状态：").append(success ? "成功" : "失败").append('\n');
            report.append("命中：").append(stats.summary());
            if (message != null && !message.isEmpty()) report.append("\n信息：").append(message);
            if (!notes.isEmpty()) {
                report.append("\n备注：");
                for (String note : notes) report.append("\n- ").append(note);
            }
            return report.toString();
        }
    }
}
