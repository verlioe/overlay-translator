import subprocess, sys
sys.path.insert(0, 'tools')
from patch_datastore_url import decode_varint, parse_entry

ADB = r'B:\Android\Sdk\platform-tools\adb.exe'
DEV, PKG = 'HA262RWW', 'com.dsh.overlaytranslator'
PB = 'files/datastore/overlay_translator_settings.preferences_pb'
LOCAL = 'artifacts/verify.pb'

subprocess.run([ADB,'-s',DEV,'shell',f'run-as {PKG} cat {PB} > /data/local/tmp/v.pb'], capture_output=True)
subprocess.run([ADB,'-s',DEV,'pull','/data/local/tmp/v.pb',LOCAL], capture_output=True)
data = open(LOCAL,'rb').read()
print(f'檔案大小 = {len(data)} bytes')
print('--- 所有欄位 ---')
i = 0
while i < len(data):
    tag, i = decode_varint(data, i)
    ln, i = decode_varint(data, i)
    entry = data[i:i+ln]; i += ln
    key, value = parse_entry(entry)
    name = (key or b'?').decode('utf-8','replace')
    kind = value[0] if value else 0
    if kind == 0x2A:
        slen, j = decode_varint(value, 1)
        val = value[j:j+slen]
        if name == 'api_key_encrypted':
            print(f'  {name:24} = <加密字串，長度 {len(val)}>  ← 你的金鑰還在嗎？')
        else:
            print(f'  {name:24} = {val.decode("utf-8","replace")!r}')
    elif kind == 0x08:
        print(f'  {name:24} = bool/varint')
    elif kind == 0x20:
        print(f'  {name:24} = int')
    else:
        print(f'  {name:24} = <型別 0x{kind:02x}>')
