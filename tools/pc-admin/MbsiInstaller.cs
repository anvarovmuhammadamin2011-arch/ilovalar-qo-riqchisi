// "MBSI PC V2.exe": the one file to share with other computers.
// A small window that installs the app for the current Windows user (no admin rights),
// adds "MBSI PC" shortcuts and opens it. build-windows.ps1 compiles it with the C# compiler
// that ships with Windows and embeds the app folder as app.zip.
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("MBSI PC V2")]
[assembly: AssemblyDescription("MBSI PC: telefonlar va kompyuterlarni boshqarish")]
[assembly: AssemblyCompany("MBSI")]
[assembly: AssemblyProduct("MBSI PC")]
[assembly: AssemblyCopyright("MBSI")]
[assembly: AssemblyVersion("2.0.0.0")]
[assembly: AssemblyFileVersion("2.0.0.0")]

static class MbsiInstaller
{
    // The folder and exe keep their first names: the admin data, the SYSTEM agent task and
    // the usage helper all point there, so an update keeps working without new links.
    const string AppFolder = "Curbox for PC";
    const string AppExe = "Curbox for PC.exe";
    const string ShortcutName = "MBSI PC";
    const string OldShortcutName = "Curbox for PC";

    static Form form;
    static Label status;
    static ProgressBar bar;
    static Button main;
    static Button close;
    static string installedExe;

    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        form = new Form();
        form.Text = "MBSI PC V2";
        form.FormBorderStyle = FormBorderStyle.FixedDialog;
        form.MaximizeBox = false;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.ClientSize = new Size(480, 250);
        form.Font = new Font("Segoe UI", 10f);
        form.BackColor = Color.White;
        try { form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }

        Label title = new Label();
        title.Text = "MBSI PC V2";
        title.Font = new Font("Segoe UI Semibold", 18f);
        title.AutoSize = true;
        title.Location = new Point(24, 20);

        Label about = new Label();
        about.Text = "Telefonlar va kompyuterlarni boshqarish dasturi.\n"
            + "Bu kompyuterga oʻrnatiladi. Administrator huquqi kerak emas.";
        about.AutoSize = false;
        about.Size = new Size(432, 48);
        about.Location = new Point(26, 66);
        about.ForeColor = Color.FromArgb(90, 90, 90);

        bar = new ProgressBar();
        bar.Location = new Point(26, 128);
        bar.Size = new Size(428, 10);
        bar.Visible = false;

        status = new Label();
        status.AutoSize = false;
        status.Size = new Size(428, 44);
        status.Location = new Point(26, 146);

        main = new Button();
        main.Text = "Oʻrnatish";
        main.Size = new Size(130, 36);
        main.Location = new Point(324, 196);
        main.Click += OnMain;

        close = new Button();
        close.Text = "Bekor qilish";
        close.Size = new Size(130, 36);
        close.Location = new Point(184, 196);
        close.Click += delegate { form.Close(); };

        form.Controls.AddRange(new Control[] { title, about, bar, status, main, close });
        form.AcceptButton = main;
        Application.Run(form);
    }

    static void OnMain(object sender, EventArgs e)
    {
        if (installedExe != null)
        {
            Open(installedExe);
            form.Close();
            return;
        }
        main.Enabled = false;
        close.Enabled = false;
        bar.Visible = true;
        status.ForeColor = Color.Black;
        status.Text = "Oʻrnatilmoqda...";
        Thread worker = new Thread(Install);
        worker.IsBackground = true;
        worker.Start();
    }

    static void Install()
    {
        string programs = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs");
        string target = Path.Combine(programs, AppFolder);
        string staging = Path.Combine(programs, AppFolder + ".new");
        string old = Path.Combine(programs, AppFolder + ".old");
        try
        {
            Directory.CreateDirectory(programs);
            DeleteQuietly(staging);
            DeleteQuietly(old);
            Extract(staging);

            StopRunningCopies(target);
            if (Directory.Exists(target))
            {
                if (!TryMove(target, old))
                {
                    DeleteQuietly(staging);
                    Fail("MBSI PC hozir ishlayapti va uni yopib boʻlmadi.\n"
                        + "Kompyuterni qayta yoqing, keyin yana urinib koʻring.");
                    return;
                }
            }
            Directory.Move(Path.Combine(staging, AppFolder), target);
            DeleteQuietly(staging);
            DeleteQuietly(old);

            string exe = Path.Combine(target, AppExe);
            MakeShortcuts(exe, target);
            Done(exe);
        }
        catch (Exception ex)
        {
            DeleteQuietly(staging);
            if (!Directory.Exists(target) && Directory.Exists(old)) TryMove(old, target);
            Fail("Oʻrnatib boʻlmadi: " + ex.Message);
        }
    }

    static void Extract(string staging)
    {
        string root = Path.GetFullPath(staging) + Path.DirectorySeparatorChar;
        using (Stream zipStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.zip"))
        using (ZipArchive zip = new ZipArchive(zipStream, ZipArchiveMode.Read))
        {
            int total = zip.Entries.Count;
            int done = 0;
            foreach (ZipArchiveEntry entry in zip.Entries)
            {
                string path = Path.GetFullPath(Path.Combine(staging, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new IOException("Bad file in package: " + entry.FullName);
                if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\"))
                {
                    Directory.CreateDirectory(path);
                }
                else
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    entry.ExtractToFile(path, true);
                }
                done++;
                if (done % 20 == 0 || done == total) Progress(done * 100 / total);
            }
        }
    }

    /** A running copy locks its files. The SYSTEM agent cannot be stopped without admin rights. */
    static void StopRunningCopies(string target)
    {
        string prefix = Path.GetFullPath(target) + Path.DirectorySeparatorChar;
        foreach (Process p in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(AppExe)))
        {
            try
            {
                string file = p.MainModule.FileName;
                if (file.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                {
                    p.Kill();
                    p.WaitForExit(5000);
                }
            }
            catch (Exception) { }
        }
    }

    static bool TryMove(string from, string to)
    {
        for (int i = 0; i < 10; i++)
        {
            try
            {
                Directory.Move(from, to);
                return true;
            }
            catch (IOException) { Thread.Sleep(500); }
            catch (UnauthorizedAccessException) { Thread.Sleep(500); }
        }
        return false;
    }

    static void DeleteQuietly(string dir)
    {
        try
        {
            if (!Directory.Exists(dir)) return;
            // Earlier installs left some files read only, which Directory.Delete refuses.
            foreach (string file in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                File.SetAttributes(file, FileAttributes.Normal);
            Directory.Delete(dir, true);
        }
        catch (Exception) { }
    }

    static void MakeShortcuts(string exe, string workDir)
    {
        string[] places = {
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            Environment.GetFolderPath(Environment.SpecialFolder.Programs)
        };
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        object shell = Activator.CreateInstance(shellType);
        foreach (string place in places)
        {
            try { File.Delete(Path.Combine(place, OldShortcutName + ".lnk")); } catch (Exception) { }
            object link = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell,
                new object[] { Path.Combine(place, ShortcutName + ".lnk") });
            Type linkType = link.GetType();
            linkType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, link, new object[] { exe });
            linkType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, link, new object[] { workDir });
            linkType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, link, new object[] { exe + ",0" });
            linkType.InvokeMember("Description", BindingFlags.SetProperty, null, link, new object[] { "MBSI PC: telefonlar va kompyuterlarni boshqarish" });
            linkType.InvokeMember("Save", BindingFlags.InvokeMethod, null, link, null);
        }
    }

    static void Open(string exe)
    {
        try
        {
            ProcessStartInfo info = new ProcessStartInfo(exe);
            info.WorkingDirectory = Path.GetDirectoryName(exe);
            Process.Start(info);
        }
        catch (Exception ex)
        {
            MessageBox.Show(form, "Ochib boʻlmadi: " + ex.Message, "MBSI PC V2");
        }
    }

    static void Progress(int percent)
    {
        form.BeginInvoke((MethodInvoker)delegate { bar.Value = Math.Min(100, percent); });
    }

    static void Done(string exe)
    {
        form.BeginInvoke((MethodInvoker)delegate
        {
            installedExe = exe;
            bar.Value = 100;
            status.ForeColor = Color.FromArgb(0, 120, 60);
            status.Text = "Tayyor! Ish stolida \"MBSI PC\" yorligʻi paydo boʻldi.";
            main.Text = "Ochish";
            main.Enabled = true;
            close.Text = "Yopish";
            close.Enabled = true;
            main.Focus();
        });
    }

    static void Fail(string message)
    {
        form.BeginInvoke((MethodInvoker)delegate
        {
            bar.Visible = false;
            status.ForeColor = Color.FromArgb(180, 30, 30);
            status.Text = message;
            main.Text = "Qayta urinish";
            main.Enabled = true;
            close.Text = "Yopish";
            close.Enabled = true;
        });
    }
}
