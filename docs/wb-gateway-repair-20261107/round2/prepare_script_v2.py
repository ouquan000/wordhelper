from pathlib import Path
root=Path(__file__).resolve().parent
src=(root/'script_harness.py').read_text(encoding='utf-8')
src=src.replace("SCR=ROOT/'scratch-scripts'","SCR=ROOT/'scratch-scripts-v2'").replace("EVD=ROOT/'script-evidence.jsonl'","EVD=ROOT/'script-v2-evidence.jsonl'").replace("ROOT/'script-results.json'","ROOT/'script-v2-results.json'")
src=src.replace('def invoke(args,timeout=20):','def invoke(args,timeout=75):').replace('invoke(guardcmd,30)','invoke(guardcmd,75)')
src=src.replace("str(ROOT/'FakeWB.cs')","str(ROOT/'FakeWB.cs')")
src=src.replace("cmd=[shutil.which('cmd.exe'),'/d','/c',str(RUN/'start-silent.cmd')]","cmd=[shutil.which('cmd.exe'),'/d','/s','/c','\"'+str(RUN/'start-silent.cmd')+'\"']")
dest=root/'script_harness_v2.py'
assert not dest.exists()
dest.write_text(src,encoding='utf-8')
print('Created fresh retry harness with 75s native command budget, independent evidence names, and explicit CMD /s quoting. No candidate source changed.')
