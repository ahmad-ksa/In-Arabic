set -x
mkdir -p shots
adb install -r InArabic-androidtv.apk
adb shell appops set com.inarabic.tv SYSTEM_ALERT_WINDOW allow
adb shell monkey -p com.inarabic.tv -c android.intent.category.LEANBACK_LAUNCHER 1
sleep 8
python3 ci/ui.py shot shots/tv-1-main.png
python3 ci/ui.py tap "اعرض سطرًا تجريبيًا"
sleep 2
python3 ci/ui.py shot shots/tv-2-test.png
adb shell input keyevent KEYCODE_HOME; sleep 2
python3 ci/ui.py tap "اعرض" ; true
python3 ci/ui.py shot shots/tv-3-home.png
true
