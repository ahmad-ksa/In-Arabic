set -x
mkdir -p shots
adb install -r InArabic-phone.apk
adb shell am start -W -n com.inarabic.phone/com.metallic.chiaki.main.MainActivity
sleep 10
adb shell dumpsys activity activities | grep -m3 -i 'mResumedActivity\|topResumed'
python3 ci/ui.py shot shots/phone-1-main.png
python3 ci/ui.py tap "More options" || python3 ci/ui.py tap "خيارات"
python3 ci/ui.py shot shots/phone-2-menu.png
python3 ci/ui.py tap "إعدادات الترجمة"
sleep 2
python3 ci/ui.py shot shots/phone-3-settings.png
adb shell input swipe 540 1700 540 500 600; sleep 1
python3 ci/ui.py shot shots/phone-4-settings2.png
adb shell input swipe 540 1700 540 500 600; sleep 1
python3 ci/ui.py shot shots/phone-5-settings3.png
adb shell input keyevent KEYCODE_BACK; sleep 2
# the + button, then "register"
adb shell input tap 960 2220
sleep 2
python3 ci/ui.py shot shots/phone-6-fab.png
python3 ci/ui.py tap "ربط البلايستيشن"
sleep 2
python3 ci/ui.py shot shots/phone-7-regist.png
adb shell input keyevent KEYCODE_BACK; sleep 1
true
