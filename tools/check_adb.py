import subprocess
import os

adb = 'C:/Users/juanm/AppData/Local/Android/Sdk/platform-tools/adb.exe'
apk = 'c:/Users/juanm/OneDrive/Documents/GitHub/personal/CascadeProjects/windsurf-project/EasyDashcam/app/build/outputs/apk/debug/app-debug.apk'

r = subprocess.run([adb, 'devices'], capture_output=True, text=True)
print('adb devices:', r.stdout, r.stderr, sep='\n')
print('APK exists:', os.path.isfile(apk))
if os.path.isfile(apk):
    print('APK size MB:', round(os.path.getsize(apk) / 1e6, 1))
