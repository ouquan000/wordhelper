using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
public class Round2FakeWB {
 public static void Main(string[] args) {
  string here=AppDomain.CurrentDomain.BaseDirectory;
  int port=int.Parse(File.ReadAllText(Path.Combine(here,"port.txt")).Trim());
  File.AppendAllText(Path.Combine(here,"starts.txt"),System.Diagnostics.Process.GetCurrentProcess().Id+" "+DateTime.UtcNow.ToString("o")+Environment.NewLine);
  TcpListener listener=new TcpListener(IPAddress.Loopback,port); listener.Start();
  Console.WriteLine("ROUND2_FAKE_WB "+port);
  for(;;) { TcpClient client=listener.AcceptTcpClient(); ThreadPool.QueueUserWorkItem(_=>Serve(client,here)); }
 }
 static void Serve(TcpClient client,string here) {
  try { using(client) { client.ReceiveTimeout=3000; NetworkStream stream=client.GetStream();
   StreamReader reader=new StreamReader(stream,Encoding.ASCII,false,1024,true); string first=reader.ReadLine(); string line;
   while(!string.IsNullOrEmpty(line=reader.ReadLine())){}
   string mode=File.Exists(Path.Combine(here,"mode.txt"))?File.ReadAllText(Path.Combine(here,"mode.txt")).Trim():"healthy";
   string body="{\"service\":\"workbuddy2api\",\"healthy\":2}"; int status=200;
   if(mode=="bad") body="{\"service\":\"foreign-service\",\"healthy\":2}";
   if(mode=="zero") body="{\"service\":\"workbuddy2api\",\"healthy\":0}";
   if(mode=="404") { status=404;body="{\"error\":\"fake-missing\"}"; }
   if(mode=="malformed") body="invalid-json";
   File.AppendAllText(Path.Combine(here,"requests.txt"),(first??"")+" "+mode+Environment.NewLine);
   byte[] payload=Encoding.UTF8.GetBytes(body);
   byte[] header=Encoding.ASCII.GetBytes("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+payload.Length+"\r\nConnection: close\r\n\r\n");
   stream.Write(header,0,header.Length);stream.Write(payload,0,payload.Length);stream.Flush();
  }}catch(Exception ex){ Console.WriteLine(ex.GetType().Name); }
 }
}
