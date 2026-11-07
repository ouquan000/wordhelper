from pathlib import Path
root=Path(__file__).resolve().parent
s=(root/'script_harness_v2.py').read_text(encoding='utf-8')
s=s.replace("SCR=ROOT/'scratch-scripts-v2'","SCR=ROOT/'scratch-launcher-v3'").replace("EVD=ROOT/'script-v2-evidence.jsonl'","EVD=ROOT/'launcher-v3-evidence.jsonl'").replace("ROOT/'script-v2-results.json'","ROOT/'launcher-v3-results.json'")
s=s.replace('cp=subprocess.run(args,capture_output=True,timeout=timeout)','cp=subprocess.run(args,cwd=RUN if str(args[0]).lower().endswith("cmd.exe") else None,capture_output=True,timeout=timeout)')
a=s.index(" for name in ('main','aux','router'):");b=s.index(' # Execute the real CMD',a);s=s[:a]+s[b:]
a=s.index(' cmd=[shutil.which(\'cmd.exe\')');b=s.index('\n',a);s=s[:a]+" cmd=[shutil.which('cmd.exe'),'/d','/c','start-silent.cmd']"+s[b:]
a=s.index(' # Native guard CLI');b=s.index(' stop(main);FOREIGN=',a);s=s[:a]+s[b:]
a=s.index(' res=invoke(guardcmd,75);rec(\'G4_native_guard_foreign\'');b=s.index('\n',a);s=s[:a]+s[b:]
a=s.index(' res=invoke(guardcmd,75);rec(\'G4_native_guard_missing_NoRestart\'');b=s.index(' # Preserve own raw runtime logs',a);s=s[:a]+s[b:]
s=s.replace('python round2/script_harness.py','python round2/launcher_v3.py').replace("cwd.name+'-fake-output.log'","'v3-'+cwd.name+'-fake-output.log'").replace("ROOT/'scripts-router-output.log'","ROOT/'launcher-v3-router-output.log'")
dest=root/'launcher_v3.py';assert not dest.exists();dest.write_text(s,encoding='utf-8')
print('Fresh launcher-only harness; Windows CMD uses own cwd and bare command. Candidate control flow unchanged; earlier diagnostics retained.')
