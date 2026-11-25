# Small adb + uiautomator helper for the screenshot run: tap by visible text / description, screenshots.
import re, subprocess, sys, time, xml.etree.ElementTree as ET

def sh(*a):
    return subprocess.run(['adb', *a], capture_output=True, text=True).stdout

def nodes():
    sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    x = sh('shell', 'cat', '/sdcard/ui.xml')
    try:
        return ET.fromstring(x[x.index('<'):]).iter('node')
    except Exception:
        return []

def tap(text, contains=True, tries=6):
    for _ in range(tries):
        for n in nodes():
            t = (n.get('text') or '') + '|' + (n.get('content-desc') or '') + '|' + (n.get('resource-id') or '')
            if (text in t) if contains else (n.get('text') == text):
                b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
                sh('shell', 'input', 'tap', str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                time.sleep(1.5)
                return True
        time.sleep(1)
    print('not found:', text)
    return False

def shot(name):
    time.sleep(1.2)
    with open(name, 'wb') as f:
        f.write(subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True).stdout)
    print('shot', name)

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'tap': tap(sys.argv[2])
    elif cmd == 'shot': shot(sys.argv[2])
