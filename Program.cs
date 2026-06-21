using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using System.Xml;
using System.Xml.Linq;
using NPOI.HWPF;

namespace OneClickRedactor
{
    internal static class EmbeddedAssemblyLoader
    {
        private static readonly IDictionary<string, string> ResourceNames = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            { "NPOI", "Embedded.NPOI.dll" },
            { "NPOI.ScratchPad.HWPF", "Embedded.NPOI.ScratchPad.HWPF.dll" },
            { "ICSharpCode.SharpZipLib", "Embedded.ICSharpCode.SharpZipLib.dll" }
        };

        public static void Initialize()
        {
            AppDomain.CurrentDomain.AssemblyResolve += ResolveEmbeddedAssembly;
        }

        private static Assembly ResolveEmbeddedAssembly(object sender, ResolveEventArgs args)
        {
            string resourceName;
            var assemblyName = new AssemblyName(args.Name).Name;
            if (!ResourceNames.TryGetValue(assemblyName, out resourceName))
            {
                return null;
            }

            var currentAssembly = Assembly.GetExecutingAssembly();
            using (var stream = currentAssembly.GetManifestResourceStream(resourceName))
            {
                if (stream == null)
                {
                    return null;
                }

                var bytes = new byte[stream.Length];
                var offset = 0;
                while (offset < bytes.Length)
                {
                    var read = stream.Read(bytes, offset, bytes.Length - offset);
                    if (read <= 0)
                    {
                        break;
                    }
                    offset += read;
                }
                return Assembly.Load(bytes);
            }
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            EmbeddedAssemblyLoader.Initialize();

            if (args != null && args.Length > 0)
            {
                CommandLineRunner.Run(args);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    internal static class CommandLineRunner
    {
        public static void Run(string[] args)
        {
            RuleFile.EnsureExists();

            var enableAddressGuess = true;
            var inputPaths = new List<string>();
            foreach (var arg in args)
            {
                if (string.Equals(arg, "--no-address-guess", StringComparison.OrdinalIgnoreCase))
                {
                    enableAddressGuess = false;
                }
                else if (string.Equals(arg, "--help", StringComparison.OrdinalIgnoreCase) || string.Equals(arg, "/?", StringComparison.OrdinalIgnoreCase))
                {
                    WriteCommandLineHelp();
                    return;
                }
                else
                {
                    inputPaths.Add(arg);
                }
            }

            var files = DiscoverFiles(inputPaths);
            var anonymizer = new Anonymizer(RuleFile.LoadRules(), enableAddressGuess);
            var results = new List<FileProcessingResult>();

            foreach (var file in files)
            {
                try
                {
                    results.Add(FileProcessor.Process(file, anonymizer));
                }
                catch (Exception ex)
                {
                    var failed = new FileProcessingResult(file);
                    failed.Success = false;
                    failed.Message = ex.Message;
                    results.Add(failed);
                }
            }

            WriteCommandLineReport(results);
        }

        private static List<string> DiscoverFiles(IEnumerable<string> paths)
        {
            var files = new List<string>();
            foreach (var path in paths)
            {
                if (File.Exists(path))
                {
                    if (FileProcessor.IsSupported(path) && !files.Contains(path, StringComparer.OrdinalIgnoreCase))
                    {
                        files.Add(path);
                    }
                }
                else if (Directory.Exists(path))
                {
                    foreach (var file in Directory.EnumerateFiles(path, "*.*", SearchOption.AllDirectories))
                    {
                        if (FileProcessor.IsSupported(file) && !files.Contains(file, StringComparer.OrdinalIgnoreCase))
                        {
                            files.Add(file);
                        }
                    }
                }
            }

            return files;
        }

        private static void WriteCommandLineReport(IList<FileProcessingResult> results)
        {
            try
            {
                var reportPath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "命令行脱敏报告.txt");
                var lines = new List<string>();
                lines.Add("一键脱敏工具命令行处理报告");
                lines.Add("生成时间：" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
                lines.Add("");
                foreach (var result in results)
                {
                    lines.Add(result.ToDetailedReport());
                    lines.Add("");
                }

                File.WriteAllLines(reportPath, lines.ToArray(), new UTF8Encoding(true));
            }
            catch
            {
            }
        }

        private static void WriteCommandLineHelp()
        {
            try
            {
                var helpPath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "命令行用法.txt");
                var lines = new[]
                {
                    "一键脱敏工具命令行用法",
                    "",
                    "一键脱敏工具.exe 文件或文件夹路径 [更多路径]",
                    "一键脱敏工具.exe --no-address-guess 文件或文件夹路径",
                    "",
                    "说明：原文件不会被修改，会生成带 _已脱敏 后缀的新文件。"
                };
                File.WriteAllLines(helpPath, lines, new UTF8Encoding(true));
            }
            catch
            {
            }
        }
    }

    internal sealed class MainForm : Form
    {
        private readonly ListBox fileList;
        private readonly TextBox logBox;
        private readonly Button addFilesButton;
        private readonly Button addFolderButton;
        private readonly Button clearButton;
        private readonly Button startButton;
        private readonly Button openRuleButton;
        private readonly CheckBox includeSubfoldersCheckBox;
        private readonly CheckBox addressGuessCheckBox;
        private readonly ProgressBar progressBar;
        private readonly Label statusLabel;
        private readonly BackgroundWorker worker;
        private readonly List<string> files;

        public MainForm()
        {
            Text = "一键脱敏工具";
            Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(900, 620);
            Size = new Size(980, 700);
            AllowDrop = true;
            DragEnter += MainForm_DragEnter;
            DragDrop += MainForm_DragDrop;

            files = new List<string>();

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.ColumnCount = 1;
            root.RowCount = 5;
            root.Padding = new Padding(14);
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 48));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 52));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            Controls.Add(root);

            var header = new TableLayoutPanel();
            header.Dock = DockStyle.Fill;
            header.ColumnCount = 2;
            header.RowCount = 1;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            root.Controls.Add(header, 0, 0);

            var titlePanel = new Panel();
            titlePanel.Dock = DockStyle.Fill;
            header.Controls.Add(titlePanel, 0, 0);

            var titleLabel = new Label();
            titleLabel.AutoSize = true;
            titleLabel.Font = new Font(Font.FontFamily, 17F, FontStyle.Bold);
            titleLabel.Text = "一键脱敏 doc / docx / xlsx / pptx / txt / csv";
            titleLabel.Location = new Point(0, 0);
            titlePanel.Controls.Add(titleLabel);

            var subtitleLabel = new Label();
            subtitleLabel.AutoSize = true;
            subtitleLabel.ForeColor = Color.FromArgb(90, 90, 90);
            subtitleLabel.Text = "原文件不会被修改；输出文件默认保存在原目录，文件名追加“_已脱敏”。";
            subtitleLabel.Location = new Point(2, 34);
            titlePanel.Controls.Add(subtitleLabel);

            openRuleButton = new Button();
            openRuleButton.Dock = DockStyle.Top;
            openRuleButton.Height = 34;
            openRuleButton.Text = "编辑补充规则";
            openRuleButton.Click += OpenRuleButton_Click;
            header.Controls.Add(openRuleButton, 1, 0);

            var listGroup = new GroupBox();
            listGroup.Dock = DockStyle.Fill;
            listGroup.Text = "待处理文件（可拖拽文件或文件夹到这里）";
            root.Controls.Add(listGroup, 0, 1);

            fileList = new ListBox();
            fileList.Dock = DockStyle.Fill;
            fileList.HorizontalScrollbar = true;
            listGroup.Controls.Add(fileList);

            var buttonPanel = new TableLayoutPanel();
            buttonPanel.Dock = DockStyle.Fill;
            buttonPanel.ColumnCount = 7;
            buttonPanel.RowCount = 1;
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 90));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            buttonPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            root.Controls.Add(buttonPanel, 0, 2);

            addFilesButton = new Button();
            addFilesButton.Text = "添加文件";
            addFilesButton.Dock = DockStyle.Fill;
            addFilesButton.Click += AddFilesButton_Click;
            buttonPanel.Controls.Add(addFilesButton, 0, 0);

            addFolderButton = new Button();
            addFolderButton.Text = "添加文件夹";
            addFolderButton.Dock = DockStyle.Fill;
            addFolderButton.Click += AddFolderButton_Click;
            buttonPanel.Controls.Add(addFolderButton, 1, 0);

            clearButton = new Button();
            clearButton.Text = "清空";
            clearButton.Dock = DockStyle.Fill;
            clearButton.Click += ClearButton_Click;
            buttonPanel.Controls.Add(clearButton, 2, 0);

            includeSubfoldersCheckBox = new CheckBox();
            includeSubfoldersCheckBox.Text = "文件夹含子目录";
            includeSubfoldersCheckBox.Checked = true;
            includeSubfoldersCheckBox.Dock = DockStyle.Fill;
            includeSubfoldersCheckBox.TextAlign = ContentAlignment.MiddleLeft;
            buttonPanel.Controls.Add(includeSubfoldersCheckBox, 3, 0);

            addressGuessCheckBox = new CheckBox();
            addressGuessCheckBox.Text = "加强地址模糊识别";
            addressGuessCheckBox.Checked = true;
            addressGuessCheckBox.Dock = DockStyle.Fill;
            addressGuessCheckBox.TextAlign = ContentAlignment.MiddleLeft;
            buttonPanel.Controls.Add(addressGuessCheckBox, 4, 0);

            startButton = new Button();
            startButton.Text = "开始脱敏";
            startButton.Dock = DockStyle.Fill;
            startButton.Font = new Font(Font.FontFamily, 10F, FontStyle.Bold);
            startButton.Click += StartButton_Click;
            buttonPanel.Controls.Add(startButton, 6, 0);

            var logGroup = new GroupBox();
            logGroup.Dock = DockStyle.Fill;
            logGroup.Text = "处理日志";
            root.Controls.Add(logGroup, 0, 3);

            logBox = new TextBox();
            logBox.Dock = DockStyle.Fill;
            logBox.Multiline = true;
            logBox.ScrollBars = ScrollBars.Vertical;
            logBox.ReadOnly = true;
            logBox.WordWrap = false;
            logGroup.Controls.Add(logBox);

            var statusPanel = new TableLayoutPanel();
            statusPanel.Dock = DockStyle.Fill;
            statusPanel.ColumnCount = 2;
            statusPanel.RowCount = 1;
            statusPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            statusPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 260));
            root.Controls.Add(statusPanel, 0, 4);

            statusLabel = new Label();
            statusLabel.Dock = DockStyle.Fill;
            statusLabel.Text = "就绪";
            statusLabel.TextAlign = ContentAlignment.MiddleLeft;
            statusPanel.Controls.Add(statusLabel, 0, 0);

            progressBar = new ProgressBar();
            progressBar.Dock = DockStyle.Fill;
            statusPanel.Controls.Add(progressBar, 1, 0);

            worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            worker.DoWork += Worker_DoWork;
            worker.ProgressChanged += Worker_ProgressChanged;
            worker.RunWorkerCompleted += Worker_RunWorkerCompleted;

            RuleFile.EnsureExists();
        }

        private void AddFilesButton_Click(object sender, EventArgs e)
        {
            using (var dialog = new OpenFileDialog())
            {
                dialog.Title = "选择需要脱敏的文件";
                dialog.Multiselect = true;
                dialog.Filter = "支持的文件|*.doc;*.docx;*.docm;*.xlsx;*.xlsm;*.pptx;*.pptm;*.txt;*.csv|Word 文档|*.doc;*.docx;*.docm|Excel 表格|*.xlsx;*.xlsm|PowerPoint|*.pptx;*.pptm|文本文件|*.txt;*.csv|所有文件|*.*";
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    AddPaths(dialog.FileNames);
                }
            }
        }

        private void AddFolderButton_Click(object sender, EventArgs e)
        {
            using (var dialog = new FolderBrowserDialog())
            {
                dialog.Description = "选择包含待脱敏文件的文件夹";
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    AddPaths(new[] { dialog.SelectedPath });
                }
            }
        }

        private void ClearButton_Click(object sender, EventArgs e)
        {
            files.Clear();
            fileList.Items.Clear();
            statusLabel.Text = "已清空";
        }

        private void OpenRuleButton_Click(object sender, EventArgs e)
        {
            RuleFile.EnsureExists();
            try
            {
                System.Diagnostics.Process.Start("notepad.exe", RuleFile.Path);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "无法打开补充规则文件：" + ex.Message, "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void StartButton_Click(object sender, EventArgs e)
        {
            if (worker.IsBusy)
            {
                return;
            }

            if (files.Count == 0)
            {
                MessageBox.Show(this, "请先添加需要脱敏的文件或文件夹。", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            SetBusy(true);
            progressBar.Value = 0;
            logBox.Clear();
            AppendLog("开始处理，共 " + files.Count + " 个文件。");
            AppendLog("补充规则文件：" + RuleFile.Path);

            var options = new ProcessingOptions();
            options.Files = files.ToArray();
            options.EnableAddressGuess = addressGuessCheckBox.Checked;
            worker.RunWorkerAsync(options);
        }

        private void MainForm_DragEnter(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                e.Effect = DragDropEffects.Copy;
            }
        }

        private void MainForm_DragDrop(object sender, DragEventArgs e)
        {
            var dropped = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (dropped != null)
            {
                AddPaths(dropped);
            }
        }

        private void AddPaths(IEnumerable<string> paths)
        {
            var discovered = new List<string>();
            foreach (var path in paths)
            {
                if (File.Exists(path))
                {
                    if (FileProcessor.IsSupported(path))
                    {
                        discovered.Add(path);
                    }
                }
                else if (Directory.Exists(path))
                {
                    var option = includeSubfoldersCheckBox.Checked ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
                    try
                    {
                        foreach (var file in Directory.EnumerateFiles(path, "*.*", option))
                        {
                            if (FileProcessor.IsSupported(file))
                            {
                                discovered.Add(file);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        AppendLog("读取文件夹失败：" + path + "；" + ex.Message);
                    }
                }
            }

            var added = 0;
            foreach (var file in discovered.OrderBy(x => x, StringComparer.CurrentCultureIgnoreCase))
            {
                if (!files.Contains(file, StringComparer.OrdinalIgnoreCase))
                {
                    files.Add(file);
                    fileList.Items.Add(file);
                    added++;
                }
            }

            statusLabel.Text = "已添加 " + added + " 个文件，当前共 " + files.Count + " 个。";
        }

        private void Worker_DoWork(object sender, DoWorkEventArgs e)
        {
            var options = (ProcessingOptions)e.Argument;
            var workerRef = (BackgroundWorker)sender;
            var customRules = RuleFile.LoadRules();
            var anonymizer = new Anonymizer(customRules, options.EnableAddressGuess);
            var allResults = new List<FileProcessingResult>();

            for (var i = 0; i < options.Files.Length; i++)
            {
                var file = options.Files[i];
                var percent = options.Files.Length == 0 ? 0 : (int)Math.Round((i * 100.0) / options.Files.Length);
                workerRef.ReportProgress(percent, "正在处理：" + file);

                FileProcessingResult result;
                try
                {
                    result = FileProcessor.Process(file, anonymizer);
                }
                catch (Exception ex)
                {
                    result = new FileProcessingResult(file);
                    result.Success = false;
                    result.Message = ex.Message;
                }

                allResults.Add(result);
                workerRef.ReportProgress(percent, result.ToLogLine());
            }

            workerRef.ReportProgress(100, "处理完成。");
            e.Result = allResults;
        }

        private void Worker_ProgressChanged(object sender, ProgressChangedEventArgs e)
        {
            if (e.ProgressPercentage >= 0 && e.ProgressPercentage <= 100)
            {
                progressBar.Value = e.ProgressPercentage;
            }

            var message = e.UserState as string;
            if (!string.IsNullOrEmpty(message))
            {
                if (message.StartsWith("正在处理：", StringComparison.Ordinal))
                {
                    statusLabel.Text = message;
                }
                else
                {
                    AppendLog(message);
                }
            }
        }

        private void Worker_RunWorkerCompleted(object sender, RunWorkerCompletedEventArgs e)
        {
            SetBusy(false);
            progressBar.Value = 100;

            if (e.Error != null)
            {
                statusLabel.Text = "处理失败";
                AppendLog("处理失败：" + e.Error.Message);
                return;
            }

            var results = e.Result as List<FileProcessingResult>;
            if (results == null)
            {
                statusLabel.Text = "处理完成";
                return;
            }

            var success = results.Count(x => x.Success);
            var changed = results.Count(x => x.Success && x.TotalReplacements > 0);
            var failed = results.Count - success;
            AppendLog("汇总：成功 " + success + " 个，命中脱敏规则 " + changed + " 个，失败 " + failed + " 个。");
            statusLabel.Text = "完成：成功 " + success + " 个，失败 " + failed + " 个。";

            TryWriteBatchReport(results);
        }

        private void SetBusy(bool busy)
        {
            addFilesButton.Enabled = !busy;
            addFolderButton.Enabled = !busy;
            clearButton.Enabled = !busy;
            startButton.Enabled = !busy;
            openRuleButton.Enabled = !busy;
            includeSubfoldersCheckBox.Enabled = !busy;
            addressGuessCheckBox.Enabled = !busy;
        }

        private void AppendLog(string message)
        {
            if (logBox.TextLength > 0)
            {
                logBox.AppendText(Environment.NewLine);
            }
            logBox.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + message);
        }

        private void TryWriteBatchReport(IList<FileProcessingResult> results)
        {
            try
            {
                var reportPath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "最近一次脱敏报告.txt");
                var lines = new List<string>();
                lines.Add("一键脱敏工具处理报告");
                lines.Add("生成时间：" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
                lines.Add("");
                foreach (var result in results)
                {
                    lines.Add(result.ToDetailedReport());
                    lines.Add("");
                }

                File.WriteAllLines(reportPath, lines.ToArray(), new UTF8Encoding(true));
                AppendLog("已生成总报告：" + reportPath);
            }
            catch (Exception ex)
            {
                AppendLog("生成总报告失败：" + ex.Message);
            }
        }
    }

    internal sealed class ProcessingOptions
    {
        public string[] Files;
        public bool EnableAddressGuess;
    }

    internal static class RuleFile
    {
        public static string Path
        {
            get { return System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "脱敏补充规则.txt"); }
        }

        public static void EnsureExists()
        {
            if (File.Exists(Path))
            {
                return;
            }

            var lines = new[]
            {
                "# 一行一条补充脱敏规则，适合添加专案中的姓名、地址、企业名、账号等。",
                "# 写法一：张三",
                "# 效果：文档里的“张三”会替换为“***”。",
                "# 写法二：某某科技有限公司=>涉案企业",
                "# 效果：左边原文会替换为右边内容。",
                "# 以 # 开头的行会被忽略。",
                ""
            };
            File.WriteAllLines(Path, lines, new UTF8Encoding(true));
        }

        public static IList<CustomRule> LoadRules()
        {
            EnsureExists();
            var rules = new List<CustomRule>();
            foreach (var rawLine in File.ReadAllLines(Path, Encoding.UTF8))
            {
                var line = rawLine.Trim();
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                {
                    continue;
                }

                var replacement = "***";
                var token = line;
                var arrowIndex = line.IndexOf("=>", StringComparison.Ordinal);
                if (arrowIndex >= 0)
                {
                    token = line.Substring(0, arrowIndex).Trim();
                    replacement = line.Substring(arrowIndex + 2).Trim();
                    if (replacement.Length == 0)
                    {
                        replacement = "***";
                    }
                }

                if (token.Length > 0)
                {
                    rules.Add(new CustomRule(token, replacement));
                }
            }

            rules.Sort(delegate(CustomRule a, CustomRule b)
            {
                return b.Token.Length.CompareTo(a.Token.Length);
            });
            return rules;
        }
    }

    internal sealed class CustomRule
    {
        public CustomRule(string token, string replacement)
        {
            Token = token;
            Replacement = replacement;
        }

        public string Token { get; private set; }
        public string Replacement { get; private set; }
    }

    internal sealed class RedactionStats
    {
        private readonly Dictionary<string, int> counts = new Dictionary<string, int>();

        public int Total
        {
            get
            {
                var total = 0;
                foreach (var value in counts.Values)
                {
                    total += value;
                }
                return total;
            }
        }

        public void Add(string category, int count)
        {
            if (count <= 0)
            {
                return;
            }

            if (!counts.ContainsKey(category))
            {
                counts[category] = 0;
            }
            counts[category] += count;
        }

        public void Merge(RedactionStats other)
        {
            foreach (var pair in other.counts)
            {
                Add(pair.Key, pair.Value);
            }
        }

        public string ToSummaryText()
        {
            if (counts.Count == 0)
            {
                return "未发现规则命中";
            }

            var parts = new List<string>();
            foreach (var pair in counts.OrderByDescending(x => x.Value).ThenBy(x => x.Key))
            {
                parts.Add(pair.Key + " " + pair.Value);
            }
            return string.Join("，", parts.ToArray());
        }
    }

    internal sealed class Anonymizer
    {
        private readonly IList<CustomRule> customRules;
        private readonly bool enableAddressGuess;
        private readonly HashSet<string> learnedNames = new HashSet<string>(StringComparer.Ordinal);
        private bool preventTextGrowthMode;

        private readonly Regex creditCodeRegex = new Regex(@"(?<![A-Za-z0-9])(?=[0-9A-HJ-NPQRTUWXY]{18}(?![A-Za-z0-9]))(?=[0-9A-HJ-NPQRTUWXY]*[A-HJ-NPQRTUWXY])[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}(?![A-Za-z0-9])", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private readonly Regex id18Regex = new Regex(@"(?<![A-Za-z0-9])([1-9]\d{5})(18|19|20)\d{2}((0[1-9])|(1[0-2]))(([0-2]\d)|(3[01]))\d{3}[\dXx](?![A-Za-z0-9])", RegexOptions.Compiled);
        private readonly Regex id15Regex = new Regex(@"(?<!\d)[1-9]\d{5}\d{2}((0[1-9])|(1[0-2]))(([0-2]\d)|(3[01]))\d{3}(?!\d)", RegexOptions.Compiled);
        private readonly Regex mobileRegex = new Regex(@"(?<!\d)(\+?86[- ]?)?(1[3-9]\d{9})(?!\d)", RegexOptions.Compiled);
        private readonly Regex landlineRegex = new Regex(@"(?<!\d)(0\d{2,3})[- ]?(\d{7,8})(?!\d)", RegexOptions.Compiled);
        private readonly Regex emailRegex = new Regex(@"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", RegexOptions.Compiled);
        private readonly Regex bankRegex = new Regex(@"(?<!\d)\d{12,19}(?!\d)", RegexOptions.Compiled);
        private readonly Regex passportRegex = new Regex(@"(?<![A-Za-z0-9])(?:E|G|P|S|D)\d{7,8}(?![A-Za-z0-9])", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private readonly Regex plateRegex = new Regex(@"(?<![\u4e00-\u9fa5A-Za-z0-9])[\u4e00-\u9fa5][A-Z][A-Z0-9]{5,6}(?![A-Za-z0-9])", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private readonly Regex nameLabelRegex = new Regex(@"((?:姓名|名字|被害人|受害人|嫌疑人|证人|联系人|负责人|法定代表人|实际控制人|经办人|申请人|被申请人|当事人|户主|所有人|收件人|发件人|开户名|户名)\s*[:：]?\s*)([\u4e00-\u9fa5·]{2,8})(?=\s|$|[，,。；;、\)）])", RegexOptions.Compiled);
        private readonly Regex explicitRoleNameRegex = new Regex(@"(?:委托诉讼代理人|申请执行人|再审申请人|代理审判员|人民陪审员|法定代理人|委托代理人|诉讼代理人|法定代表人|实际控制人|犯罪嫌疑人|被申请人|被上诉人|被执行人|法官助理|合议庭成员|审判员|审判长|书记员|执行员|检察员|公诉人|辩护人|被告人|申请人|上诉人|申诉人|被申诉人|被害人|受害人|嫌疑人|第三人|鉴定人|翻译人员|负责人|联系人|经办人|当事人|委托人|受托人|中介|原告|被告|证人|户主|所有人)(?:[一二三四五六七八九十\d]+)?\s*[:：]\s*(?<name>[\u4e00-\u9fa5·]{2,4})(?=\s|$|[，,。；;、（(])", RegexOptions.Compiled);
        private readonly Regex inlineRoleNameRegex = new Regex(@"(?:委托诉讼代理人|申请执行人|再审申请人|代理审判员|人民陪审员|法定代理人|委托代理人|诉讼代理人|法定代表人|犯罪嫌疑人|被申请人|被上诉人|被执行人|法官助理|审判员|审判长|书记员|执行员|检察员|公诉人|辩护人|被告人|申请人|上诉人|申诉人|被申诉人|被害人|受害人|嫌疑人|第三人|鉴定人|负责人|联系人|经办人|委托人|受托人|中介|原告|被告|证人)(?:[一二三四五六七八九十\d]+)?\s*(?<name>(?:(?:欧阳|太史|端木|上官|司马|东方|独孤|南宫|万俟|闻人|夏侯|诸葛|尉迟|公羊|赫连|澹台|皇甫|宗政|濮阳|公冶|太叔|申屠|公孙|慕容|仲孙|钟离|长孙|宇文|司徒|鲜于|司空|闾丘|子车|亓官|司寇|巫马|公西|颛孙|壤驷|公良|漆雕|乐正|宰父|谷梁|拓跋|夹谷|轩辕|令狐|段干|百里|呼延|东郭|南门|羊舌|微生|梁丘|左丘|东门|西门)[\u4e00-\u9fa5·]{1,2}|[赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉龚程邢滑裴陆荣翁荀羊於惠甄曲封芮储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲台从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公][\u4e00-\u9fa5·]{1,2}))(?=\s|$|[，,。；;、：:（）()]|的|与|和|及|向|由|在|担任|独任|到庭|未到庭|男|女|系|称|说|表示|陈述|辩称|认为|主张|提出|提交|展示|告知|保证|签字|代理|参加)", RegexOptions.Compiled);
        private const string CourtRolePattern = @"(?:委托诉讼代理人|申请执行人|再审申请人|代理审判员|人民陪审员|法定代理人|委托代理人|诉讼代理人|法定代表人|实际控制人|犯罪嫌疑人|被申请人|被上诉人|被执行人|法官助理|合议庭成员|审判员|审判长|书记员|执行员|检察员|公诉人|辩护人|被告人|申请人|上诉人|申诉人|被申诉人|被害人|受害人|嫌疑人|第三人|鉴定人|翻译人员|负责人|联系人|经办人|当事人|委托人|受托人|中介|原告|被告|证人|户主|所有人)";
        private const string PoliceRolePattern = @"(?:公安机关负责人|办案部门负责人|证据保全申请人|委托诉讼代理人|法定代理人|案件承办人|主办侦查员|协办侦查员|辨认主持人|主持辨认人|犯罪嫌疑人|违法嫌疑人|违法行为人|行政相对人|被询问人|被讯问人|被调查人|被检查人|被搜查人|被处罚人|被传唤人|被拘传人|受害单位联系人|证据提供人|物品持有人|物品所有人|办案民警|承办民警|主办民警|协办民警|现场民警|办案人员|侦查人员|调查人员|询问人员|讯问人员|记录人员|执法人员|接警人员|处警人员|勘验人员|检查人员|搜查人员|鉴定人员|审核人员|审批人员|翻译人员|被辨认人|被告知人|受送达人|涉案人员|报案人|报警人|控告人|举报人|扭送人|投案人|自首人|被害人|受害人|见证人|辨认人|陪衬人|鉴定人|勘验人|检查人|搜查人|提取人|扣押人|保管人|领取人|送达人|告知人|陈述人|申辩人|监护人|近亲属|家属|驾驶人|驾驶员|承办人|审核人|审批人|询问人|讯问人|调查人|侦查员|记录人|接警人|处警人|负责人|联系人|经办人|民警|警员)";
        private const string LikelyPersonNamePattern = @"(?:(?:欧阳|太史|端木|上官|司马|东方|独孤|南宫|万俟|闻人|夏侯|诸葛|尉迟|公羊|赫连|澹台|皇甫|宗政|濮阳|公冶|太叔|申屠|公孙|慕容|仲孙|钟离|长孙|宇文|司徒|鲜于|司空|闾丘|子车|亓官|司寇|巫马|公西|颛孙|壤驷|公良|漆雕|乐正|宰父|谷梁|拓跋|夹谷|轩辕|令狐|段干|百里|呼延|东郭|南门|羊舌|微生|梁丘|左丘|东门|西门)[\u4e00-\u9fa5·]{1,2}|[赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉龚程邢滑裴陆荣翁荀羊於惠甄曲封芮储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲台从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公][\u4e00-\u9fa5·]{1,2}|[\u4e00-\u9fa5]{1,6}·[\u4e00-\u9fa5·]{1,10})";
        private readonly Regex roleNameListRegex = new Regex(@"(?:" + CourtRolePattern + "|" + PoliceRolePattern + @")(?:[一二三四五六七八九十\d]+)?[ \t]*[:：][ \t]*(?<name>" + LikelyPersonNamePattern + @")(?:[ \t]*(?:、|,|，|和|及|[ \t]+)[ \t]*(?<name>" + LikelyPersonNamePattern + @")){0,7}", RegexOptions.Compiled);
        private readonly Regex policeInlineRoleNameRegex = new Regex(PoliceRolePattern + @"(?:[一二三四五六七八九十\d]+)?[ \t]*(?<name>" + LikelyPersonNamePattern + @")(?=\s|$|[，,。；;、：:（）()]|的|与|和|及|向|由|在|担任|负责|办理|承办|询问|讯问|调查|侦查|记录|接警|处警|出警|到场|到案|到庭|未到庭|男|女|系|称|说|表示|陈述|辩称|认为|主张|提出|提交|展示|告知|保证|签字|签名|捺印|核对|执行|出示|参加)", RegexOptions.Compiled);
        private readonly Regex addressLabelRegex = new Regex(@"((?:家庭住址|户籍地址|户籍地|现住址|居住地|住址|住所地|地址|通讯地址|联系地址|注册地址|经营地址|办公地址|送达地址)\s*[:：]?\s*)([^\r\n，,。；;]{4,100})", RegexOptions.Compiled);
        private readonly Regex companyLabelRegex = new Regex(@"((?:单位名称|企业名称|公司名称|机构名称|客户名称|供应商|采购方|销售方|用人单位|工作单位)\s*[:：]?\s*)([^\r\n，,。；;]{2,80})", RegexOptions.Compiled);
        private readonly Regex companyNameRegex = new Regex(@"[\u4e00-\u9fa5A-Za-z0-9（）()·\-]{2,60}(?:有限责任公司|股份有限公司|集团有限公司|有限公司|分公司|合作社|个体工商户|事务所|研究所|医院|学校)", RegexOptions.Compiled);
        private readonly Regex ageLabelRegex = new Regex(@"((?:年龄|年纪|岁数)\s*[:：]?\s*)([1-9]\d?|1[01]\d|120)(\s*(?:岁|周岁)?)", RegexOptions.Compiled);
        private readonly Regex ageSuffixRegex = new Regex(@"(?<!\d)([1-9]\d?|1[01]\d|120)\s*(岁|周岁)(?!\d)", RegexOptions.Compiled);
        private readonly Regex addressGuessRegex = new Regex(@"[\u4e00-\u9fa5]{2,}(?:省|自治区|市|区|县|旗)[\u4e00-\u9fa5A-Za-z0-9一二三四五六七八九十零〇号弄栋幢单元室楼层路街道巷镇乡村组\-]{5,100}(?:号|室|单元|楼|层|村|组)", RegexOptions.Compiled);

        public Anonymizer(IList<CustomRule> customRules, bool enableAddressGuess)
        {
            this.customRules = customRules ?? new List<CustomRule>();
            this.enableAddressGuess = enableAddressGuess;
        }

        public string Anonymize(string text, RedactionStats stats)
        {
            if (string.IsNullOrEmpty(text))
            {
                return text;
            }

            LearnNamesFromText(text);
            var result = text;

            foreach (var rule in customRules)
            {
                result = ReplaceLiteral(result, rule.Token, rule.Replacement, "补充规则", stats);
            }

            foreach (var name in learnedNames.OrderByDescending(n => n.Length).ToList())
            {
                result = ReplaceLiteral(result, name, MaskName(name), "姓名（角色识别）", stats);
            }

            result = ReplaceRegex(result, companyLabelRegex, "企业/单位", stats, delegate(Match m)
            {
                return m.Groups[1].Value + "[单位已脱敏]";
            });

            result = ReplaceRegex(result, addressLabelRegex, "地址", stats, delegate(Match m)
            {
                return m.Groups[1].Value + "[地址已脱敏]";
            });

            result = ReplaceRegex(result, nameLabelRegex, "姓名", stats, delegate(Match m)
            {
                return m.Groups[1].Value + MaskName(m.Groups[2].Value);
            });

            result = ReplaceRegex(result, creditCodeRegex, "统一社会信用代码", stats, delegate(Match m)
            {
                return MaskKeep(m.Value.ToUpperInvariant(), 4, 4);
            });

            result = ReplaceRegex(result, id18Regex, "身份证号", stats, delegate(Match m)
            {
                return MaskKeep(m.Value, 3, 4);
            });

            result = ReplaceRegex(result, id15Regex, "身份证号", stats, delegate(Match m)
            {
                return MaskKeep(m.Value, 3, 3);
            });

            result = ReplaceRegex(result, mobileRegex, "手机号", stats, delegate(Match m)
            {
                return m.Groups[1].Value + MaskKeep(m.Groups[2].Value, 3, 4);
            });

            result = ReplaceRegex(result, landlineRegex, "座机号", stats, delegate(Match m)
            {
                return m.Groups[1].Value + "-****" + Right(m.Groups[2].Value, 2);
            });

            result = ReplaceRegex(result, emailRegex, "邮箱", stats, delegate(Match m)
            {
                var value = m.Value;
                var at = value.IndexOf('@');
                if (at <= 0)
                {
                    return "***";
                }
                return value.Substring(0, 1) + "***" + value.Substring(at);
            });

            result = ReplaceRegex(result, passportRegex, "护照号", stats, delegate(Match m)
            {
                return MaskKeep(m.Value.ToUpperInvariant(), 1, 2);
            });

            result = ReplaceRegex(result, plateRegex, "车牌号", stats, delegate(Match m)
            {
                return MaskKeep(m.Value.ToUpperInvariant(), 2, 1);
            });

            result = ReplaceRegex(result, bankRegex, "银行卡/账号", stats, delegate(Match m)
            {
                return MaskKeep(m.Value, 4, 4);
            });

            result = ReplaceRegex(result, companyNameRegex, "企业/单位", stats, delegate(Match m)
            {
                return "[单位已脱敏]";
            });

            result = ReplaceRegex(result, ageLabelRegex, "年龄", stats, delegate(Match m)
            {
                return m.Groups[1].Value + "**" + m.Groups[3].Value;
            });

            result = ReplaceRegex(result, ageSuffixRegex, "年龄", stats, delegate(Match m)
            {
                return "**" + m.Groups[2].Value;
            });

            if (enableAddressGuess)
            {
                result = ReplaceRegex(result, addressGuessRegex, "地址", stats, delegate(Match m)
                {
                    return "[地址已脱敏]";
                });
            }

            return result;
        }

        public string AnonymizeWithoutTextGrowth(string text, RedactionStats stats)
        {
            var previousMode = preventTextGrowthMode;
            preventTextGrowthMode = true;
            try
            {
                return Anonymize(text, stats);
            }
            finally
            {
                preventTextGrowthMode = previousMode;
            }
        }

        public void LearnNamesFromText(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return;
            }

            LearnNamesFromMatches(explicitRoleNameRegex.Matches(text));
            LearnNamesFromMatches(inlineRoleNameRegex.Matches(text));
            LearnNamesFromMatches(roleNameListRegex.Matches(text));
            LearnNamesFromMatches(policeInlineRoleNameRegex.Matches(text));
        }

        private void LearnNamesFromMatches(MatchCollection matches)
        {
            foreach (Match match in matches)
            {
                foreach (Capture capture in match.Groups["name"].Captures)
                {
                    var name = capture.Value.Trim();
                    if (IsLearnableName(name))
                    {
                        learnedNames.Add(name);
                    }
                }
            }
        }

        private static bool IsLearnableName(string name)
        {
            if (string.IsNullOrEmpty(name) || name.Length < 2 || name.Length > 18 || name.IndexOf('某') >= 0)
            {
                return false;
            }

            var nonNameFragments = new[]
            {
                "人员", "民警", "情况", "内容", "单位", "公司", "机关", "部门", "地址", "电话",
                "身份", "材料", "记录", "签名", "指印", "时间", "地点", "是否", "什么", "如何",
                "为何", "知道", "清楚", "回答", "以上", "完毕", "不详", "没有", "无异议"
            };
            return !nonNameFragments.Any(fragment => name.IndexOf(fragment, StringComparison.Ordinal) >= 0);
        }

        public string AnonymizeStrongIdentifiersOnly(string text, RedactionStats stats)
        {
            if (string.IsNullOrEmpty(text))
            {
                return text;
            }

            var result = text;
            result = ReplaceRegex(result, creditCodeRegex, "统一社会信用代码", stats, delegate(Match m) { return MaskKeep(m.Value.ToUpperInvariant(), 4, 4); });
            result = ReplaceRegex(result, id18Regex, "身份证号", stats, delegate(Match m) { return MaskKeep(m.Value, 3, 4); });
            result = ReplaceRegex(result, id15Regex, "身份证号", stats, delegate(Match m) { return MaskKeep(m.Value, 3, 3); });
            result = ReplaceRegex(result, mobileRegex, "手机号", stats, delegate(Match m) { return m.Groups[1].Value + MaskKeep(m.Groups[2].Value, 3, 4); });
            result = ReplaceRegex(result, landlineRegex, "座机号", stats, delegate(Match m) { return m.Groups[1].Value + "-****" + Right(m.Groups[2].Value, 2); });
            result = ReplaceRegex(result, bankRegex, "银行卡/账号", stats, delegate(Match m) { return MaskKeep(m.Value, 4, 4); });
            return result;
        }

        private string ReplaceLiteral(string input, string token, string replacement, string category, RedactionStats stats)
        {
            var count = 0;
            var result = Regex.Replace(input, Regex.Escape(token), delegate(Match m)
            {
                count++;
                return preventTextGrowthMode ? LimitReplacementLength(m.Value, replacement) : replacement;
            });
            stats.Add(category, count);
            return result;
        }

        private string ReplaceRegex(string input, Regex regex, string category, RedactionStats stats, MatchEvaluator evaluator)
        {
            var count = 0;
            var result = regex.Replace(input, delegate(Match m)
            {
                count++;
                var replacement = evaluator(m);
                return preventTextGrowthMode ? LimitReplacementLength(m.Value, replacement) : replacement;
            });
            stats.Add(category, count);
            return result;
        }

        private static string LimitReplacementLength(string original, string replacement)
        {
            if (string.IsNullOrEmpty(replacement) || replacement.Length <= original.Length)
            {
                return replacement;
            }

            var compact = replacement
                .Replace("[地址已脱敏]", "***")
                .Replace("[单位已脱敏]", "***");
            if (compact.Length > original.Length)
            {
                compact = Regex.Replace(compact, @"\*{2,}", "*");
            }
            return compact.Length <= original.Length ? compact : compact.Substring(0, original.Length);
        }

        private static string MaskName(string name)
        {
            if (string.IsNullOrEmpty(name))
            {
                return "***";
            }

            if (name.Length <= 2)
            {
                return name.Substring(0, 1) + "某";
            }

            return name.Substring(0, 1) + new string('某', Math.Min(name.Length - 1, 3));
        }

        private static string MaskKeep(string value, int left, int right)
        {
            if (string.IsNullOrEmpty(value))
            {
                return "***";
            }

            if (value.Length <= left + right)
            {
                return new string('*', value.Length);
            }

            return value.Substring(0, left) + new string('*', value.Length - left - right) + value.Substring(value.Length - right);
        }

        private static string Right(string value, int count)
        {
            if (string.IsNullOrEmpty(value))
            {
                return "";
            }

            if (value.Length <= count)
            {
                return value;
            }

            return value.Substring(value.Length - count);
        }
    }

    internal sealed class FileProcessingResult
    {
        public FileProcessingResult(string sourcePath)
        {
            SourcePath = sourcePath;
            Success = true;
            Stats = new RedactionStats();
            Notes = new List<string>();
        }

        public string SourcePath { get; private set; }
        public string OutputPath { get; set; }
        public bool Success { get; set; }
        public string Message { get; set; }
        public RedactionStats Stats { get; private set; }
        public IList<string> Notes { get; private set; }

        public int TotalReplacements
        {
            get { return Stats.Total; }
        }

        public string ToLogLine()
        {
            if (!Success)
            {
                return "失败：" + SourcePath + "；" + Message;
            }

            return "完成：" + System.IO.Path.GetFileName(SourcePath) + "；" + Stats.ToSummaryText() + "；输出：" + OutputPath;
        }

        public string ToDetailedReport()
        {
            var builder = new StringBuilder();
            builder.AppendLine("原文件：" + SourcePath);
            builder.AppendLine("输出文件：" + (OutputPath ?? ""));
            builder.AppendLine("状态：" + (Success ? "成功" : "失败"));
            builder.AppendLine("命中：" + Stats.ToSummaryText());
            if (!string.IsNullOrEmpty(Message))
            {
                builder.AppendLine("信息：" + Message);
            }
            if (Notes.Count > 0)
            {
                builder.AppendLine("备注：");
                foreach (var note in Notes)
                {
                    builder.AppendLine("- " + note);
                }
            }
            return builder.ToString().TrimEnd();
        }
    }

    internal static class FileProcessor
    {
        private static readonly HashSet<string> SupportedExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".doc", ".docx", ".docm", ".xlsx", ".xlsm", ".pptx", ".pptm", ".txt", ".csv"
        };

        public static bool IsSupported(string path)
        {
            return SupportedExtensions.Contains(System.IO.Path.GetExtension(path));
        }

        public static FileProcessingResult Process(string sourcePath, Anonymizer anonymizer)
        {
            if (!File.Exists(sourcePath))
            {
                throw new FileNotFoundException("文件不存在。", sourcePath);
            }

            var result = new FileProcessingResult(sourcePath);
            var extension = System.IO.Path.GetExtension(sourcePath).ToLowerInvariant();
            result.OutputPath = BuildOutputPath(sourcePath);

            if (extension == ".txt" || extension == ".csv")
            {
                ProcessTextFile(sourcePath, result.OutputPath, anonymizer, result);
            }
            else if (extension == ".doc")
            {
                ProcessBinaryWordFile(sourcePath, result.OutputPath, anonymizer, result);
            }
            else
            {
                ProcessOpenXmlFile(sourcePath, result.OutputPath, extension, anonymizer, result);
            }

            WriteSidecarReport(result);
            return result;
        }

        private static string BuildOutputPath(string sourcePath)
        {
            var directory = System.IO.Path.GetDirectoryName(sourcePath);
            var name = System.IO.Path.GetFileNameWithoutExtension(sourcePath);
            var extension = System.IO.Path.GetExtension(sourcePath);
            var candidate = System.IO.Path.Combine(directory, name + "_已脱敏" + extension);
            if (!File.Exists(candidate))
            {
                return candidate;
            }

            return System.IO.Path.Combine(directory, name + "_已脱敏_" + DateTime.Now.ToString("yyyyMMddHHmmss") + extension);
        }

        private static void ProcessTextFile(string sourcePath, string outputPath, Anonymizer anonymizer, FileProcessingResult result)
        {
            var bytes = File.ReadAllBytes(sourcePath);
            var encoding = DetectEncoding(bytes);
            var text = encoding.GetString(bytes);
            text = StripBomCharacter(text);
            var changed = anonymizer.Anonymize(text, result.Stats);
            File.WriteAllText(outputPath, changed, new UTF8Encoding(true));
        }

        private static Encoding DetectEncoding(byte[] bytes)
        {
            if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF)
            {
                return new UTF8Encoding(true);
            }

            if (bytes.Length >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE)
            {
                return Encoding.Unicode;
            }

            if (bytes.Length >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF)
            {
                return Encoding.BigEndianUnicode;
            }

            var utf8 = new UTF8Encoding(false, true);
            try
            {
                utf8.GetString(bytes);
                return utf8;
            }
            catch
            {
                try
                {
                    return Encoding.GetEncoding("GB18030");
                }
                catch
                {
                    return Encoding.Default;
                }
            }
        }

        private static string StripBomCharacter(string text)
        {
            if (!string.IsNullOrEmpty(text) && text[0] == '\uFEFF')
            {
                return text.Substring(1);
            }
            return text;
        }

        private static void ProcessBinaryWordFile(string sourcePath, string outputPath, Anonymizer anonymizer, FileProcessingResult result)
        {
            HWPFDocument document;
            using (var input = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                try
                {
                    document = new HWPFDocument(input);
                }
                catch (Exception ex)
                {
                    throw new InvalidDataException("无法读取该 .doc 文件。文件可能已加密、损坏，或并非 Word 97-2003 二进制格式。", ex);
                }
            }

            var ranges = GetBinaryWordRanges(document);
            foreach (var range in ranges)
            {
                LearnNamesFromBinaryWordRange(range, anonymizer);
            }

            var edits = BuildBinaryWordEdits(ranges, anonymizer, result.Stats);

            if (edits.Count == 0)
            {
                File.Copy(sourcePath, outputPath, false);
                return;
            }

            result.Notes.Add("旧版 .doc 使用兼容脱敏模式；为保护二进制文档结构，过长的自定义替换内容会自动缩短。");
            ApplyBinaryWordEdits(document, edits);

            var tempPath = outputPath + ".tmp";
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }

            try
            {
                using (var output = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                    document.Write(output);
                }

                VerifyBinaryWordOutput(tempPath, edits);

                File.Move(tempPath, outputPath);
            }
            catch
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
                throw;
            }
        }

        private static void VerifyBinaryWordOutput(string path, IEnumerable<BinaryWordParagraphEdit> edits)
        {
            using (var verificationStream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                var verificationDocument = new HWPFDocument(verificationStream);
                var verificationRange = verificationDocument.GetOverallRange();
                var verificationText = verificationRange == null ? null : verificationRange.Text;
                if (string.IsNullOrEmpty(verificationText))
                {
                    throw new InvalidDataException("写出后的 .doc 文档无法读取正文。");
                }

                foreach (var edit in edits)
                {
                    if (!string.IsNullOrEmpty(edit.ChangedText)
                        && verificationText.IndexOf(edit.ChangedText, StringComparison.Ordinal) < 0)
                    {
                        throw new InvalidDataException("写出后的 .doc 文档未通过脱敏内容校验。");
                    }
                }
            }
        }

        private static IList<NPOI.HWPF.UserModel.Range> GetBinaryWordRanges(HWPFDocument document)
        {
            var ranges = new List<NPOI.HWPF.UserModel.Range>();
            TryAddBinaryWordRange(ranges, delegate { return document.GetRange(); });
            TryAddBinaryWordRange(ranges, delegate { return document.GetHeaderStoryRange(); });
            TryAddBinaryWordRange(ranges, delegate { return document.GetFootnoteRange(); });
            TryAddBinaryWordRange(ranges, delegate { return document.GetEndnoteRange(); });
            TryAddBinaryWordRange(ranges, delegate { return document.GetCommentsRange(); });
            TryAddBinaryWordRange(ranges, delegate { return document.GetMainTextboxRange(); });
            return ranges;
        }

        private static void TryAddBinaryWordRange(IList<NPOI.HWPF.UserModel.Range> ranges, Func<NPOI.HWPF.UserModel.Range> factory)
        {
            try
            {
                var range = factory();
                if (range != null && range.EndOffset > range.StartOffset)
                {
                    ranges.Add(range);
                }
            }
            catch
            {
                // Some legacy documents do not contain every optional Word story.
            }
        }

        private static void LearnNamesFromBinaryWordRange(NPOI.HWPF.UserModel.Range range, Anonymizer anonymizer)
        {
            for (var i = 0; i < range.NumParagraphs; i++)
            {
                anonymizer.LearnNamesFromText(range.GetParagraph(i).Text);
            }
        }

        private static IList<BinaryWordParagraphEdit> BuildBinaryWordEdits(
            IEnumerable<NPOI.HWPF.UserModel.Range> ranges,
            Anonymizer anonymizer,
            RedactionStats stats)
        {
            var edits = new List<BinaryWordParagraphEdit>();
            var seenParagraphs = new HashSet<string>(StringComparer.Ordinal);
            foreach (var range in ranges)
            {
                for (var i = 0; i < range.NumParagraphs; i++)
                {
                    var paragraph = range.GetParagraph(i);
                    var original = TrimTrailingWordControlCharacters(paragraph.Text);
                    if (string.IsNullOrEmpty(original))
                    {
                        continue;
                    }

                    var paragraphKey = paragraph.StartOffset + ":" + original.Length;
                    if (!seenParagraphs.Add(paragraphKey))
                    {
                        continue;
                    }

                    var changed = anonymizer.AnonymizeWithoutTextGrowth(original, stats);
                    if (!string.Equals(original, changed, StringComparison.Ordinal))
                    {
                        edits.Add(new BinaryWordParagraphEdit(paragraph.StartOffset, original, changed));
                    }
                }
            }
            return edits;
        }

        private static string TrimTrailingWordControlCharacters(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return text;
            }

            var length = text.Length;
            while (length > 0 && (text[length - 1] == '\r' || text[length - 1] == '\n' || text[length - 1] == '\a'))
            {
                length--;
            }
            return length == text.Length ? text : text.Substring(0, length);
        }

        private static void ApplyBinaryWordEdits(HWPFDocument document, IEnumerable<BinaryWordParagraphEdit> edits)
        {
            foreach (var edit in edits.OrderByDescending(e => e.StartOffset))
            {
                var overallRange = document.GetOverallRange();
                var relativeOffset = edit.StartOffset - overallRange.StartOffset;
                var currentText = overallRange.Text;
                if (relativeOffset < 0
                    || relativeOffset + edit.OriginalText.Length > currentText.Length
                    || !string.Equals(currentText.Substring(relativeOffset, edit.OriginalText.Length), edit.OriginalText, StringComparison.Ordinal))
                {
                    throw new InvalidDataException("处理 .doc 段落时发现文档内部位置异常，已停止写出以避免损坏文件。");
                }

                overallRange.ReplaceText(edit.OriginalText, edit.ChangedText, relativeOffset);
            }
        }

        private sealed class BinaryWordParagraphEdit
        {
            public BinaryWordParagraphEdit(int startOffset, string originalText, string changedText)
            {
                StartOffset = startOffset;
                OriginalText = originalText;
                ChangedText = changedText;
            }

            public int StartOffset { get; private set; }
            public string OriginalText { get; private set; }
            public string ChangedText { get; private set; }
        }

        private static void ProcessOpenXmlFile(string sourcePath, string outputPath, string extension, Anonymizer anonymizer, FileProcessingResult result)
        {
            var tempPath = outputPath + ".tmp";
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }

            try
            {
                using (var source = ZipFile.OpenRead(sourcePath))
                using (var destination = ZipFile.Open(tempPath, ZipArchiveMode.Create))
                {
                    foreach (var entry in source.Entries)
                    {
                        var newEntry = destination.CreateEntry(entry.FullName, CompressionLevel.Optimal);
                        newEntry.LastWriteTime = entry.LastWriteTime;

                        if (entry.FullName.EndsWith("/", StringComparison.Ordinal))
                        {
                            continue;
                        }

                        if (ShouldProcessXmlEntry(entry.FullName, extension))
                        {
                            try
                            {
                                var xmlBytes = ProcessXmlEntry(entry, extension, anonymizer, result.Stats);
                                using (var target = newEntry.Open())
                                {
                                    target.Write(xmlBytes, 0, xmlBytes.Length);
                                }
                            }
                            catch (Exception ex)
                            {
                                result.Notes.Add("XML 片段未能处理，已原样保留：" + entry.FullName + "；" + ex.Message);
                                CopyEntry(entry, newEntry);
                            }
                        }
                        else
                        {
                            CopyEntry(entry, newEntry);
                        }
                    }
                }

                if (File.Exists(outputPath))
                {
                    File.Delete(outputPath);
                }
                File.Move(tempPath, outputPath);
            }
            finally
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
            }
        }

        private static bool ShouldProcessXmlEntry(string entryName, string extension)
        {
            var name = entryName.Replace('\\', '/');
            if (!name.EndsWith(".xml", StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            if (extension == ".docx" || extension == ".docm")
            {
                return name.StartsWith("word/", StringComparison.OrdinalIgnoreCase)
                    || name.StartsWith("docProps/", StringComparison.OrdinalIgnoreCase);
            }

            if (extension == ".xlsx" || extension == ".xlsm")
            {
                return name.StartsWith("xl/", StringComparison.OrdinalIgnoreCase)
                    || name.StartsWith("docProps/", StringComparison.OrdinalIgnoreCase);
            }

            if (extension == ".pptx" || extension == ".pptm")
            {
                return name.StartsWith("ppt/", StringComparison.OrdinalIgnoreCase)
                    || name.StartsWith("docProps/", StringComparison.OrdinalIgnoreCase);
            }

            return false;
        }

        private static byte[] ProcessXmlEntry(ZipArchiveEntry entry, string extension, Anonymizer anonymizer, RedactionStats stats)
        {
            XDocument document;
            using (var stream = entry.Open())
            {
                document = XDocument.Load(stream, LoadOptions.PreserveWhitespace);
            }

            LearnNamesFromDocument(document, extension, anonymizer);
            var processed = new HashSet<XElement>();

            if (extension == ".docx" || extension == ".docm" || extension == ".pptx" || extension == ".pptm")
            {
                ProcessTextContainers(document, "p", anonymizer, stats, processed);
                ProcessTextNodes(document, anonymizer, stats, processed);
                ProcessLeafTextElements(document, anonymizer, stats, processed, false);
            }
            else if (extension == ".xlsx" || extension == ".xlsm")
            {
                ProcessTextContainers(document, "si", anonymizer, stats, processed);
                ProcessTextContainers(document, "is", anonymizer, stats, processed);
                ProcessTextContainers(document, "text", anonymizer, stats, processed);
                ProcessTextNodes(document, anonymizer, stats, processed);
                ProcessWorksheetValues(document, anonymizer, stats);
                ProcessLeafTextElements(document, anonymizer, stats, processed, true);
            }

            using (var memory = new MemoryStream())
            {
                var settings = new XmlWriterSettings();
                settings.Encoding = new UTF8Encoding(false);
                settings.OmitXmlDeclaration = document.Declaration == null;
                settings.Indent = false;
                using (var writer = XmlWriter.Create(memory, settings))
                {
                    document.Save(writer);
                }
                return memory.ToArray();
            }
        }

        private static void LearnNamesFromDocument(XDocument document, string extension, Anonymizer anonymizer)
        {
            string containerLocalName;
            if (extension == ".docx" || extension == ".docm" || extension == ".pptx" || extension == ".pptm")
            {
                containerLocalName = "p";
            }
            else if (extension == ".xlsx" || extension == ".xlsm")
            {
                containerLocalName = "si";
            }
            else
            {
                return;
            }

            foreach (var container in document.Descendants().Where(e => e.Name.LocalName == containerLocalName))
            {
                var text = string.Concat(container.Descendants().Where(e => e.Name.LocalName == "t").Select(e => e.Value));
                anonymizer.LearnNamesFromText(text);
            }
        }

        private static void ProcessTextContainers(XDocument document, string containerLocalName, Anonymizer anonymizer, RedactionStats stats, HashSet<XElement> processed)
        {
            var containers = document.Descendants().Where(e => e.Name.LocalName == containerLocalName).ToList();
            foreach (var container in containers)
            {
                var textNodes = container.Descendants().Where(e => e.Name.LocalName == "t").ToList();
                if (textNodes.Count == 0)
                {
                    continue;
                }

                ReplaceCombinedText(textNodes, anonymizer, stats);
                foreach (var node in textNodes)
                {
                    processed.Add(node);
                }
            }
        }

        private static void ProcessTextNodes(XDocument document, Anonymizer anonymizer, RedactionStats stats, HashSet<XElement> processed)
        {
            var textNodes = document.Descendants().Where(e => e.Name.LocalName == "t" && !processed.Contains(e)).ToList();
            foreach (var node in textNodes)
            {
                var changed = anonymizer.Anonymize(node.Value, stats);
                if (!string.Equals(node.Value, changed, StringComparison.Ordinal))
                {
                    node.Value = changed;
                    SetPreserveSpace(node);
                }
                processed.Add(node);
            }
        }

        private static void ProcessLeafTextElements(XDocument document, Anonymizer anonymizer, RedactionStats stats, HashSet<XElement> processed, bool skipSpreadsheetValues)
        {
            var elements = document.Descendants()
                .Where(e => !e.HasElements && !processed.Contains(e) && !string.IsNullOrEmpty(e.Value))
                .ToList();

            foreach (var element in elements)
            {
                if (skipSpreadsheetValues && (element.Name.LocalName == "v" || element.Name.LocalName == "f"))
                {
                    continue;
                }

                if (element.Name.LocalName == "t")
                {
                    continue;
                }

                var changed = anonymizer.Anonymize(element.Value, stats);
                if (!string.Equals(element.Value, changed, StringComparison.Ordinal))
                {
                    element.Value = changed;
                    SetPreserveSpace(element);
                }
            }
        }

        private static void ProcessWorksheetValues(XDocument document, Anonymizer anonymizer, RedactionStats stats)
        {
            var cells = document.Descendants().Where(e => e.Name.LocalName == "c").ToList();
            foreach (var cell in cells)
            {
                var typeAttr = cell.Attribute("t");
                var type = typeAttr == null ? "" : typeAttr.Value;
                if (string.Equals(type, "s", StringComparison.OrdinalIgnoreCase) || string.Equals(type, "b", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var valueElement = cell.Elements().FirstOrDefault(e => e.Name.LocalName == "v");
                if (valueElement == null || string.IsNullOrEmpty(valueElement.Value))
                {
                    continue;
                }

                var changed = anonymizer.AnonymizeStrongIdentifiersOnly(valueElement.Value, stats);
                if (!string.Equals(valueElement.Value, changed, StringComparison.Ordinal))
                {
                    valueElement.Value = changed;
                    cell.SetAttributeValue("t", "str");
                }
            }
        }

        private static void ReplaceCombinedText(IList<XElement> textNodes, Anonymizer anonymizer, RedactionStats stats)
        {
            var builder = new StringBuilder();
            foreach (var node in textNodes)
            {
                builder.Append(node.Value);
            }

            var original = builder.ToString();
            var changed = anonymizer.Anonymize(original, stats);
            if (string.Equals(original, changed, StringComparison.Ordinal))
            {
                return;
            }

            textNodes[0].Value = changed;
            SetPreserveSpace(textNodes[0]);
            for (var i = 1; i < textNodes.Count; i++)
            {
                textNodes[i].Value = "";
            }
        }

        private static void SetPreserveSpace(XElement element)
        {
            XNamespace xml = "http://www.w3.org/XML/1998/namespace";
            element.SetAttributeValue(xml + "space", "preserve");
        }

        private static void CopyEntry(ZipArchiveEntry source, ZipArchiveEntry destination)
        {
            using (var sourceStream = source.Open())
            using (var targetStream = destination.Open())
            {
                sourceStream.CopyTo(targetStream);
            }
        }

        private static void WriteSidecarReport(FileProcessingResult result)
        {
            try
            {
                var reportPath = result.OutputPath + ".脱敏报告.txt";
                File.WriteAllText(reportPath, result.ToDetailedReport(), new UTF8Encoding(true));
            }
            catch
            {
            }
        }
    }
}
