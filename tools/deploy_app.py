"""One-command dev loop: build -> install -> launch (optionally --logcat)."""
import os
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = (os.environ.get('ADB') or
       os.path.join(os.environ.get('ANDROID_HOME',
                    os.environ.get('ANDROID_SDK_ROOT', '')),
                    'platform-tools', 'adb.exe'))
APK = os.path.join(PROJ, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
PKG = 'com.juanm.easydashcam'


def find_gradle():
    return os.path.join(PROJ, 'gradlew.bat' if os.name == 'nt' else 'gradlew')


def run(cmd, cwd=None, env=None, tail=2500, timeout=None):
    print('$', ' '.join(cmd))
    r = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True,
                       text=True, timeout=timeout, check=False)
    out = (r.stdout or '') + (r.stderr or '')
    print(out[-tail:])
    return r.returncode


def build():
    env = dict(os.environ)
    jbr = r'C:\Program Files\Android\Android Studio\jbr'
    if 'JAVA_HOME' not in env and os.path.isdir(jbr):
        env['JAVA_HOME'] = jbr
    rc = run([find_gradle(), 'assembleDebug', '--console=plain'],
             cwd=PROJ, env=env, timeout=900)
    if rc != 0:
        sys.exit('BUILD FAILED')
    if not os.path.isfile(APK):
        sys.exit('BUILD OK but APK missing: ' + APK)


def install_and_launch():
    r = subprocess.run([ADB, "devices"], capture_output=True, text=True, check=False)
    online = [l.split()[0] for l in r.stdout.splitlines()[1:]
              if l.strip().endswith('device')]
    if not online:
        sys.exit('No authorized device found — plug the phone, unlock it, '
                 'enable USB debugging, accept the on-phone prompt')
    if run([ADB, 'install', '-r', APK], timeout=180) != 0:
        print('\nInstall blocked. MIUI: enable "Install via USB", keep the '
              'phone unlocked, and accept the on-phone dialog; or copy the '
              'APK via MTP and install from the file manager.')
        sys.exit(1)
    run([ADB, 'shell', 'monkey', '-p', PKG, '-c',
         'android.intent.category.LAUNCHER', '1'], tail=200)


def logcat():
    print('--- live logcat (Ctrl+C to stop) ---')
    run([ADB, 'logcat', '-d', PKG + ':I', '*:S'], tail=1500)
    proc = subprocess.Popen([ADB, 'logcat', PKG + ':I', '*:S'],
                            stdout=subprocess.PIPE, text=True)
    try:
        proc.wait()
    except KeyboardInterrupt:
        proc.kill()


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--logcat':
        logcat()
        raise SystemExit
    build()
    install_and_launch()
    print('\nDeployed. Use `python deploy_app.py --logcat` for logs.')
