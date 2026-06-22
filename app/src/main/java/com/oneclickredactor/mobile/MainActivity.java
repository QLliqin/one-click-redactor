package com.oneclickredactor.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILES = 101;
    private static final int REQUEST_FOLDER = 102;
    private static final int REQUEST_OUTPUT = 103;

    private static final int NAVY = Color.rgb(18, 63, 107);
    private static final int BLUE = Color.rgb(18, 103, 196);
    private static final int SURFACE = Color.rgb(244, 247, 250);
    private static final int TEXT = Color.rgb(23, 50, 77);
    private static final int MUTED = Color.rgb(94, 113, 132);
    private static final int DIVIDER = Color.rgb(216, 225, 234);
    private static final int SUCCESS = Color.rgb(24, 134, 75);
    private static final int ERROR = Color.rgb(179, 38, 30);

    private final List<QueueItem> queue = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private DocumentFile outputDirectory;
    private Uri outputTreeUri;
    private boolean processing;

    private LinearLayout queueContainer;
    private TextView queueCount;
    private TextView outputLabel;
    private TextView logText;
    private TextView progressLabel;
    private ProgressBar progressBar;
    private Button startButton;
    private Button openResultButton;
    private CheckBox recurseFolders;
    private CheckBox addressGuess;
    private CheckBox skipGenerated;
    private CheckBox batchReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        preferences = getSharedPreferences("one_click_redactor", MODE_PRIVATE);
        restoreOutputDirectory();
        setContentView(buildScreen());
        appendLog("应用已就绪。全部文件仅在本机离线处理。");
        renderQueue();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildScreen() {
        LinearLayout root = vertical();
        root.setBackgroundColor(SURFACE);
        View header = buildHeader();
        root.addView(header);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // Android 15 draws edge-to-edge by default. Keep status icons above,
            // rather than on top of, the product mark and title.
            header.setPadding(dp(18), dp(14) + insets.getSystemWindowInsetTop(), dp(16), dp(14));
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(14), dp(16), dp(18));
        content.addView(buildToolbar());
        content.addView(space(12));
        content.addView(buildOptionsCard());
        content.addView(space(12));
        content.addView(buildQueueSection());
        content.addView(space(12));
        content.addView(buildLogCard());
        scroll.addView(content, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildBottomBar());
        return root;
    }

    private View buildHeader() {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(16), dp(14));
        header.setBackgroundColor(NAVY);

        ImageView icon = new ImageView(this);
        icon.setImageResource(com.oneclickredactor.mobile.R.drawable.app_icon);
        icon.setContentDescription("一键脱敏工具图标");
        header.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titles = vertical();
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(12);
        TextView title = label("一键脱敏工具", 22, Color.WHITE, true);
        TextView subtitle = label("Android 专业版 · 完全离线", 13, Color.rgb(205, 224, 244), false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, titleParams);

        TextView version = label("v1.0", 12, Color.rgb(205, 224, 244), true);
        header.addView(version);
        return header;
    }

    private View buildToolbar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout toolbar = horizontal();
        toolbar.addView(commandButton("添加文件", false, v -> chooseFiles()));
        toolbar.addView(commandButton("添加文件夹", false, v -> chooseFolder()));
        toolbar.addView(commandButton("编辑规则", false, v -> editRules()));
        openResultButton = commandButton("打开结果", false, v -> openLatestResult());
        openResultButton.setEnabled(false);
        toolbar.addView(openResultButton);
        toolbar.addView(commandButton("清空列表", false, v -> clearQueue()));
        toolbar.addView(commandButton("关于", false, v -> showAbout()));
        scroller.addView(toolbar);
        return scroller;
    }

    private View buildOptionsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("处理选项"));

        recurseFolders = option("文件夹包含子目录", true);
        addressGuess = option("加强地址模糊识别", true);
        skipGenerated = option("跳过已脱敏文件和报告", true);
        batchReport = option("生成批次总报告", true);
        card.addView(recurseFolders);
        card.addView(addressGuess);
        card.addView(skipGenerated);
        card.addView(batchReport);

        View divider = space(1);
        divider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams dividerParams = matchHeight(dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(10);
        card.addView(divider, dividerParams);

        TextView outputTitle = label("输出位置", 14, TEXT, true);
        card.addView(outputTitle);
        LinearLayout outputRow = horizontal();
        outputRow.setGravity(Gravity.CENTER_VERTICAL);
        outputLabel = label(outputDirectory == null ? "尚未选择输出文件夹" : displayOutputName(), 14,
                outputDirectory == null ? MUTED : SUCCESS, false);
        LinearLayout.LayoutParams outputTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        outputTextParams.rightMargin = dp(10);
        outputRow.addView(outputLabel, outputTextParams);
        outputRow.addView(commandButton("选择文件夹", false, v -> chooseOutputDirectory()));
        card.addView(outputRow);
        TextView unchanged = label("原文件保持不变，结果写入上方文件夹", 12, SUCCESS, false);
        LinearLayout.LayoutParams unchangedParams = matchWrap();
        unchangedParams.topMargin = dp(6);
        card.addView(unchanged, unchangedParams);
        return card;
    }

    private View buildQueueSection() {
        LinearLayout card = card();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle("待处理文件");
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        queueCount = label("0 个文件", 13, MUTED, false);
        heading.addView(queueCount);
        card.addView(heading);

        TextView hint = label("可从手机存储、聊天软件或网盘文件提供器中选择", 12, MUTED, false);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.bottomMargin = dp(8);
        card.addView(hint, hintParams);

        queueContainer = vertical();
        card.addView(queueContainer);
        return card;
    }

    private View buildLogCard() {
        LinearLayout card = card();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(sectionTitle("处理日志"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clear = textButton("清空日志", v -> {
            logs.clear();
            renderLog();
        });
        heading.addView(clear);
        card.addView(heading);

        logText = label("", 13, TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setLineSpacing(0, 1.15f);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logText.setMinHeight(dp(76));
        logText.setPadding(0, dp(4), 0, 0);
        card.addView(logText, matchWrap());
        return card;
    }

    private View buildBottomBar() {
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(12));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(8));

        LinearLayout progressColumn = vertical();
        progressLabel = label("等待添加文件", 13, MUTED, false);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressColumn.addView(progressLabel);
        LinearLayout.LayoutParams progressParams = matchHeight(dp(6));
        progressParams.topMargin = dp(5);
        progressColumn.addView(progressBar, progressParams);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        columnParams.rightMargin = dp(14);
        bar.addView(progressColumn, columnParams);

        startButton = commandButton("开始脱敏", true, v -> startProcessing());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(dp(126), dp(50));
        bar.addView(startButton, startParams);
        return bar;
    }

    private void chooseFiles() {
        if (processing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/csv", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-word.document.macroenabled.12",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel.sheet.macroenabled.12",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint.presentation.macroenabled.12",
                "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_FILES);
    }

    private void chooseFolder() {
        if (processing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    private void chooseOutputDirectory() {
        if (processing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OUTPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_FILES) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) addFileUri(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addFileUri(data.getData());
            }
            renderQueue();
        } else if (requestCode == REQUEST_FOLDER && data.getData() != null) {
            persistPermission(data.getData(), false);
            addFolder(DocumentFile.fromTreeUri(this, data.getData()), recurseFolders.isChecked());
            renderQueue();
        } else if (requestCode == REQUEST_OUTPUT && data.getData() != null) {
            persistPermission(data.getData(), true);
            outputTreeUri = data.getData();
            outputDirectory = DocumentFile.fromTreeUri(this, outputTreeUri);
            preferences.edit().putString("output_tree", outputTreeUri.toString()).apply();
            outputLabel.setText(displayOutputName());
            outputLabel.setTextColor(SUCCESS);
            appendLog("输出位置：" + displayOutputName());
        }
    }

    private void persistPermission(Uri uri, boolean write) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | (write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some providers grant session-only access. The current operation remains usable.
        }
    }

    private void restoreOutputDirectory() {
        String saved = preferences.getString("output_tree", null);
        if (saved == null) return;
        try {
            outputTreeUri = Uri.parse(saved);
            outputDirectory = DocumentFile.fromTreeUri(this, outputTreeUri);
            if (outputDirectory == null || !outputDirectory.exists() || !outputDirectory.canWrite()) {
                outputDirectory = null;
                outputTreeUri = null;
            }
        } catch (Exception ignored) {
            outputDirectory = null;
            outputTreeUri = null;
        }
    }

    private void addFolder(DocumentFile directory, boolean recursive) {
        if (directory == null || !directory.isDirectory()) {
            toast("无法读取该文件夹。");
            return;
        }
        int before = queue.size();
        collectFolder(directory, recursive, new HashSet<>());
        appendLog("已从文件夹添加 " + (queue.size() - before) + " 个待处理文件。");
    }

    private void collectFolder(DocumentFile directory, boolean recursive, Set<String> visited) {
        if (directory == null || !visited.add(directory.getUri().toString())) return;
        DocumentFile[] children;
        try {
            children = directory.listFiles();
        } catch (Exception ex) {
            appendLog("无法读取目录：" + safeMessage(ex));
            return;
        }
        for (DocumentFile child : children) {
            if (child.isDirectory() && recursive) collectFolder(child, true, visited);
            else if (child.isFile()) addDocumentFile(child);
        }
    }

    private void addFileUri(Uri uri) {
        persistPermission(uri, false);
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        if (file != null) addDocumentFile(file);
        else {
            String name = queryName(uri);
            addQueueItem(uri, name, querySize(uri));
        }
    }

    private void addDocumentFile(DocumentFile file) {
        String name = file.getName() == null ? "未命名文件" : file.getName();
        addQueueItem(file.getUri(), name, file.length());
    }

    private void addQueueItem(Uri uri, String name, long size) {
        if (!FileRedactor.isSupported(name)) return;
        if (skipGenerated.isChecked() && FileRedactor.isGeneratedArtifact(name)) {
            appendLog("已跳过已脱敏文件或报告：" + name);
            return;
        }
        for (QueueItem existing : queue) if (existing.uri.equals(uri)) return;
        queue.add(new QueueItem(uri, name, size));
        appendLog("已添加：" + name);
    }

    private void clearQueue() {
        if (processing) return;
        queue.clear();
        renderQueue();
        progressBar.setProgress(0);
        progressLabel.setText("等待添加文件");
        openResultButton.setEnabled(false);
        appendLog("已清空待处理列表。");
    }

    private void renderQueue() {
        if (queueContainer == null) return;
        queueContainer.removeAllViews();
        queueCount.setText(String.format(Locale.CHINA, "%d 个文件", queue.size()));
        if (queue.isEmpty()) {
            TextView empty = label("尚未添加文件\n支持 DOC、DOCX、DOCM、XLSX、XLSM、PPTX、PPTM、TXT、CSV", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(24), dp(10), dp(24));
            queueContainer.addView(empty, matchWrap());
        } else {
            for (QueueItem item : queue) queueContainer.addView(buildQueueRow(item));
        }
        if (!processing) startButton.setEnabled(!queue.isEmpty());
    }

    private View buildQueueRow(QueueItem item) {
        LinearLayout row = vertical();
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(rounded(Color.rgb(248, 251, 254), DIVIDER, 10));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout detail = vertical();
        TextView name = label(item.name, 15, TEXT, true);
        name.setMaxLines(2);
        detail.addView(name);
        String meta = extensionLabel(item.name) + " · " + formatBytes(item.size);
        detail.addView(label(meta, 12, MUTED, false));
        top.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!processing && item.state != State.PROCESSING) {
            top.addView(textButton("移除", v -> {
                queue.remove(item);
                renderQueue();
            }));
        }
        row.addView(top);

        TextView state = label(item.statusText(), 13, item.statusColor(), false);
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(7);
        row.addView(state, stateParams);
        if (item.outputUri != null) {
            row.setOnClickListener(v -> openUri(item.outputUri, mimeForName(item.outputName)));
            row.setContentDescription(item.name + "，处理完成，点按打开结果");
        }
        return row;
    }

    private void startProcessing() {
        if (processing) return;
        if (queue.isEmpty()) {
            toast("请先添加待处理文件。");
            return;
        }
        if (outputDirectory == null || !outputDirectory.canWrite()) {
            toast("请先选择可写入的输出文件夹。");
            chooseOutputDirectory();
            return;
        }
        processing = true;
        startButton.setEnabled(false);
        openResultButton.setEnabled(false);
        progressBar.setProgress(0);
        for (QueueItem item : queue) {
            item.state = State.WAITING;
            item.hits = 0;
            item.error = null;
            item.outputUri = null;
        }
        renderQueue();
        appendLog("开始脱敏，共 " + queue.size() + " 个文件。");

        String rulesText = preferences.getString("custom_rules", RedactionEngine.DEFAULT_RULES);
        boolean guess = addressGuess.isChecked();
        boolean writeBatch = batchReport.isChecked();
        worker.execute(() -> processQueue(rulesText, guess, writeBatch));
    }

    private void processQueue(String rulesText, boolean guess, boolean writeBatch) {
        List<FileRedactor.Result> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int changed = 0;
        for (int index = 0; index < queue.size(); index++) {
            QueueItem item = queue.get(index);
            item.state = State.PROCESSING;
            int current = index;
            runOnUiThread(() -> {
                progressLabel.setText(String.format(Locale.CHINA, "正在处理第 %d/%d 个文件", current + 1, queue.size()));
                progressBar.setProgress(current * 100 / queue.size());
                renderQueue();
            });
            try {
                byte[] source = readUri(item.uri);
                RedactionEngine engine = new RedactionEngine(RedactionEngine.parseRules(rulesText), guess);
                FileRedactor.Result result = FileRedactor.process(item.name, source, engine);
                String outputName = FileRedactor.uniqueOutputName(item.name, name -> outputDirectory.findFile(name) != null);
                result.outputName = outputName;
                DocumentFile output = outputDirectory.createFile(mimeForName(outputName), outputName);
                if (output == null) throw new IOException("无法在输出文件夹创建结果文件。");
                writeUri(output.getUri(), result.outputBytes);
                writeSidecarReport(result);

                item.outputName = outputName;
                item.outputUri = output.getUri();
                item.hits = result.stats.total();
                item.state = State.SUCCESS;
                results.add(result);
                success++;
                if (item.hits > 0) changed++;
                int hits = item.hits;
                runOnUiThread(() -> appendLog("完成：" + item.name + "，命中 " + hits + " 项。"));
            } catch (Throwable ex) {
                item.state = State.ERROR;
                item.error = safeMessage(ex);
                FileRedactor.Result failure = new FileRedactor.Result(item.name, "");
                failure.success = false;
                failure.message = item.error;
                results.add(failure);
                failed++;
                runOnUiThread(() -> appendLog("失败：" + item.name + "；" + item.error));
            }
            int completed = index + 1;
            runOnUiThread(() -> {
                progressBar.setProgress(completed * 100 / queue.size());
                renderQueue();
            });
        }

        if (writeBatch) {
            try {
                writeBatchReport(results);
            } catch (Exception ex) {
                runOnUiThread(() -> appendLog("批次报告写入失败：" + safeMessage(ex)));
            }
        }
        int finalSuccess = success;
        int finalChanged = changed;
        int finalFailed = failed;
        runOnUiThread(() -> finishProcessing(finalSuccess, finalChanged, finalFailed));
    }

    private void finishProcessing(int success, int changed, int failed) {
        processing = false;
        progressBar.setProgress(100);
        progressLabel.setText(String.format(Locale.CHINA, "完成：成功 %d，失败 %d", success, failed));
        appendLog("汇总：成功 " + success + " 个，命中脱敏规则 " + changed + " 个，失败 " + failed + " 个。");
        openResultButton.setEnabled(latestOutput() != null);
        startButton.setEnabled(!queue.isEmpty());
        renderQueue();
        new AlertDialog.Builder(this)
                .setTitle(failed == 0 ? "脱敏完成" : "处理完成（含失败项）")
                .setMessage("成功 " + success + " 个\n命中脱敏规则 " + changed + " 个\n失败 " + failed + " 个\n\n原文件均未修改。")
                .setPositiveButton("打开结果", (dialog, which) -> openLatestResult())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void writeSidecarReport(FileRedactor.Result result) throws IOException {
        String preferred = result.outputName + ".脱敏报告.txt";
        String name = uniquePlainName(preferred);
        DocumentFile report = outputDirectory.createFile("text/plain", name);
        if (report == null) throw new IOException("无法创建单文件脱敏报告。");
        writeUri(report.getUri(), withUtf8Bom(result.detailedReport()));
    }

    private void writeBatchReport(List<FileRedactor.Result> results) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        String name = uniquePlainName("批次脱敏报告_" + stamp + ".txt");
        StringBuilder report = new StringBuilder();
        report.append("一键脱敏工具 Android 批次报告\n");
        report.append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
        report.append("文件数量：").append(results.size()).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            report.append("==== ").append(i + 1).append(" ====" ).append("\n");
            report.append(results.get(i).detailedReport()).append("\n\n");
        }
        DocumentFile output = outputDirectory.createFile("text/plain", name);
        if (output == null) throw new IOException("无法创建批次报告。");
        writeUri(output.getUri(), withUtf8Bom(report.toString().trim()));
        runOnUiThread(() -> appendLog("批次报告已生成：" + name));
    }

    private String uniquePlainName(String preferred) {
        if (outputDirectory.findFile(preferred) == null) return preferred;
        int dot = preferred.lastIndexOf('.');
        String stem = dot < 0 ? preferred : preferred.substring(0, dot);
        String ext = dot < 0 ? "" : preferred.substring(dot);
        for (int i = 1; i < 1000; i++) {
            String candidate = stem + "_" + i + ext;
            if (outputDirectory.findFile(candidate) == null) return candidate;
        }
        return stem + "_" + System.currentTimeMillis() + ext;
    }

    private void editRules() {
        if (processing) return;
        LinearLayout body = vertical();
        int padding = dp(20);
        body.setPadding(padding, dp(4), padding, 0);
        TextView help = label("一行一条。只写原文时替换为 ***；也可写“原文=>替换内容”。以 # 开头的行会被忽略。", 13, MUTED, false);
        body.addView(help);
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setText(preferences.getString("custom_rules", RedactionEngine.DEFAULT_RULES));
        editor.setTextSize(14);
        editor.setMinLines(12);
        editor.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams editorParams = matchWrap();
        editorParams.topMargin = dp(10);
        body.addView(editor, editorParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("补充脱敏规则")
                .setView(body)
                .setPositiveButton("保存", (d, which) -> {
                    preferences.edit().putString("custom_rules", editor.getText().toString()).apply();
                    appendLog("补充规则已保存，共 " + RedactionEngine.parseRules(editor.getText().toString()).size() + " 条。");
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复说明", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> editor.setText(RedactionEngine.DEFAULT_RULES)));
        dialog.show();
    }

    private void showAbout() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("关于一键脱敏工具")
                .setMessage("Android 专业版 v1.0.0\n\n完全离线运行，不申请网络权限；原文件不会被修改。\n\n支持 DOC、DOCX、DOCM、XLSX、XLSM、PPTX、PPTM、TXT、CSV。\n\n" + readRawText(com.oneclickredactor.mobile.R.raw.third_party_notices))
                .setPositiveButton("关闭", null)
                .setNeutralButton("开源许可", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Apache License 2.0")
                        .setMessage(readRawText(com.oneclickredactor.mobile.R.raw.apache_license_2_0))
                        .setPositiveButton("关闭", null)
                        .show()));
        dialog.show();
    }

    private String readRawText(int resourceId) {
        try (InputStream input = getResources().openRawResource(resourceId)) {
            return new String(readStream(input), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "无法读取许可说明：" + safeMessage(ex);
        }
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开源文件。");
            return readStream(input);
        }
    }

    private byte[] readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private void writeUri(Uri uri, byte[] bytes) throws IOException {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("无法写入结果文件。");
            output.write(bytes);
            output.flush();
        }
    }

    private byte[] withUtf8Bom(String text) {
        byte[] value = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] output = new byte[value.length + 3];
        output[0] = (byte) 0xEF;
        output[1] = (byte) 0xBB;
        output[2] = (byte) 0xBF;
        System.arraycopy(value, 0, output, 3, value.length);
        return output;
    }

    private void openLatestResult() {
        QueueItem latest = latestOutput();
        if (latest == null) {
            toast("暂无可打开的结果。");
            return;
        }
        openUri(latest.outputUri, mimeForName(latest.outputName));
    }

    private QueueItem latestOutput() {
        for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i).outputUri != null) return queue.get(i);
        return null;
    }

    private void openUri(Uri uri, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "打开或发送脱敏结果"));
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return uri.getLastPathSegment() == null ? "未命名文件" : uri.getLastPathSegment();
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        }
        return 0;
    }

    private String displayOutputName() {
        if (outputDirectory == null) return "尚未选择输出文件夹";
        String name = outputDirectory.getName();
        return name == null || name.isEmpty() ? "已选择输出文件夹" : name;
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        logs.add(time + "  " + message);
        while (logs.size() > 40) logs.remove(0);
        renderLog();
    }

    private void renderLog() {
        if (logText != null) logText.setText(logs.isEmpty() ? "暂无日志" : String.join("\n", logs));
    }

    private static String extensionLabel(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "文件" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static String mimeForName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".docm")) return "application/vnd.ms-word.document.macroenabled.12";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xlsm")) return "application/vnd.ms-excel.sheet.macroenabled.12";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".pptm")) return "application/vnd.ms-powerpoint.presentation.macroenabled.12";
        return "application/octet-stream";
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "大小未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isEmpty())) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(rounded(Color.WHITE, DIVIDER, 12));
        card.setElevation(dp(1));
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView title = label(text, 16, NAVY, true);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        title.setLayoutParams(params);
        return title;
    }

    private CheckBox option(String text, boolean checked) {
        CheckBox option = new CheckBox(this);
        option.setText(text);
        option.setTextSize(14);
        option.setTextColor(TEXT);
        option.setChecked(checked);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(dp(42));
        option.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{BLUE, MUTED}));
        return option;
    }

    private Button commandButton(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(primary ? Color.WHITE : NAVY);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(rounded(primary ? BLUE : Color.WHITE, primary ? BLUE : DIVIDER, 9));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private Button textButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(58));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private View space(int heightDp) {
        FrameLayout spacer = new FrameLayout(this);
        spacer.setMinimumHeight(dp(heightDp));
        return spacer;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum State { WAITING, PROCESSING, SUCCESS, ERROR }

    private static final class QueueItem {
        final Uri uri;
        final String name;
        final long size;
        State state = State.WAITING;
        int hits;
        String error;
        String outputName;
        Uri outputUri;

        QueueItem(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }

        String statusText() {
            switch (state) {
                case PROCESSING: return "处理中";
                case SUCCESS: return "已完成 · 命中 " + hits + " 项 · 点按打开结果";
                case ERROR: return "失败 · " + (error == null ? "未知错误" : error);
                default: return "等待中";
            }
        }

        int statusColor() {
            switch (state) {
                case SUCCESS: return SUCCESS;
                case ERROR: return ERROR;
                case PROCESSING: return BLUE;
                default: return MUTED;
            }
        }
    }
}
