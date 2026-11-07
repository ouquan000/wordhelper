using System; using System.IO;
public class Round2Netstat {
 public static void Main() {
  string modeFile=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"qq-mode.txt");
  string mode=File.Exists(modeFile)?File.ReadAllText(modeFile).Trim():"offline";
  Console.WriteLine("  Proto  Local Address          Foreign Address        State           PID");
  if(mode=="online") Console.WriteLine("  TCP    127.0.0.1:62371        127.0.0.1:62372        ESTABLISHED     12345");
  File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"netstat-calls.txt"),DateTime.UtcNow.ToString("o")+" "+mode+Environment.NewLine);
 }
}
