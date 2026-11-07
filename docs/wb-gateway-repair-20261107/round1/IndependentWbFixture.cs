// Independent native fixture and cmd PowerShell shim. No production ports or data.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

class IndependentWbFixture {
    static JavaScriptSerializer Json = new JavaScriptSerializer();
    static readonly object Sync = new object();
    static Dictionary<string, object> ReadObject(string p) { return Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(p, Encoding.UTF8)); }
    static string S(Dictionary<string, object> d, string key) { return Convert.ToString(d[key]); }
    static void Record(string p, object value) { lock (Sync) { File.AppendAllText(p, Json.Serialize(value) + Environment.NewLine, new UTF8Encoding(false)); } }
    static string Quote(string s) {
        StringBuilder b = new StringBuilder("\""); int n = 0;
        foreach (char c in s) {
            if (c == '\\') { n++; continue; }
            if (c == '"') { b.Append('\\', n * 2 + 1); b.Append(c); n = 0; continue; }
            b.Append('\\', n); n = 0; b.Append(c);
        }
        b.Append('\\', n * 2); b.Append('"'); return b.ToString();
    }
    static int Shim(string[] args) {
        var control = ReadObject(Path.Combine(Environment.CurrentDirectory, "shim-control.json"));
        Record(S(control, "calls"), new { kind = "powershell", argv = args });
        int file = Array.FindIndex(args, x => x.Equals("-File", StringComparison.OrdinalIgnoreCase));
        if (file < 0 || file + 1 >= args.Length) return 88;
        if (Path.GetFileName(args[file + 1]).Equals("wb-config-sanitize.ps1", StringComparison.OrdinalIgnoreCase)) return Convert.ToInt32(control["sanitizeCode"]);
        if (!Path.GetFileName(args[file + 1]).Equals("wb-service-check.ps1", StringComparison.OrdinalIgnoreCase)) return 89;
        var forwarded = new List<string>();
        forwarded.AddRange(new string[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", S(control, "bridge"), "-IndependentTargetHelper", args[file + 1], "-IndependentPortMap", S(control, "portMap") });
        for (int i = file + 2; i < args.Length; i++) forwarded.Add(args[i]);
        var quoted = new List<string>(); foreach (string a in forwarded) quoted.Add(Quote(a));
        var startInfo = new ProcessStartInfo(S(control, "powershell"), String.Join(" ", quoted.ToArray())) { UseShellExecute = false };
        startInfo.EnvironmentVariables["PATH"] = S(control, "originalPath");
        var p = Process.Start(startInfo);
        p.WaitForExit(); int code = p.ExitCode; p.Dispose(); return code;
    }
    static int Serve(Dictionary<string, object> cfg) {
        int port = Convert.ToInt32(cfg["port"]);
        if (port <= 1024 || port == 8788 || port == 8789 || port == 8790 || port == 6199) return 90;
        string receipt = S(cfg, "receiptFile"), bodyFile = S(cfg, "bodyFile"), stopToken = S(cfg, "stopToken"), pidFile = S(cfg, "pidFile");
        Record(receipt, new { kind = "launch", pid = Process.GetCurrentProcess().Id, port = port, exe = Process.GetCurrentProcess().MainModule.FileName });
        var listener = new TcpListener(IPAddress.Loopback, port);
        listener.ExclusiveAddressUse = true;
        listener.Start();
        File.WriteAllText(pidFile, Process.GetCurrentProcess().Id.ToString(), new UTF8Encoding(false));
        bool stopping = false;
        var deadline = DateTime.UtcNow.AddMinutes(8);
        try {
            while (!stopping && DateTime.UtcNow < deadline) {
                if (!listener.Pending()) { Thread.Sleep(15); continue; }
                using (TcpClient client = listener.AcceptTcpClient()) {
                    client.ReceiveTimeout = 2000; client.SendTimeout = 2000;
                    using (NetworkStream stream = client.GetStream()) {
                        var request = new List<byte>(); byte[] one = new byte[1];
                        while (request.Count < 8192 && stream.Read(one, 0, 1) == 1) {
                            request.Add(one[0]); int n = request.Count;
                            if (n >= 4 && request[n-4] == 13 && request[n-3] == 10 && request[n-2] == 13 && request[n-1] == 10) break;
                        }
                        string first = Encoding.ASCII.GetString(request.ToArray()).Split('\n')[0].Trim();
                        string[] parts = first.Split(' '); string target = parts.Length >= 2 ? parts[1] : "";
                        int status = 200; string contentType = "application/json"; string text;
                        if (target == "/__independent_stop/" + stopToken) { stopping = true; text = "{\"stopped\":true}"; }
                        else {
                            var mode = ReadObject(bodyFile); status = Convert.ToInt32(mode["status"]); contentType = S(mode, "contentType"); text = S(mode, "body");
                            Record(receipt, new { kind = "http", pid = Process.GetCurrentProcess().Id, method = parts[0], path = target });
                            if (mode.ContainsKey("delayMs")) Thread.Sleep(Convert.ToInt32(mode["delayMs"]));
                        }
                        byte[] body = Encoding.UTF8.GetBytes(text);
                        byte[] headers = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + " Fixture\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.Length + "\r\nConnection: close\r\n\r\n");
                        try { stream.Write(headers, 0, headers.Length); stream.Write(body, 0, body.Length); stream.Flush(); } catch (IOException) { }
                    }
                }
            }
        } finally { listener.Stop(); Record(receipt, new { kind = "exit", pid = Process.GetCurrentProcess().Id, stopped = stopping }); }
        return 0;
    }
    static int Main(string[] args) {
        try {
            if (Path.GetFileName(Process.GetCurrentProcess().MainModule.FileName).Equals("powershell.exe", StringComparison.OrdinalIgnoreCase)) return Shim(args);
            if (args.Length == 2 && args[0] == "-config") return Serve(ReadObject(Path.GetFullPath(args[1])));
            return 91;
        } catch (Exception e) { Console.Error.WriteLine("INDEPENDENT_FIXTURE_ERROR " + e.GetType().Name); return 92; }
    }
}
