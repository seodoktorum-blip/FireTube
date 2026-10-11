@echo off
setlocal
rem ============================================================
rem  FireTube filtre guncelleyici (adb)
rem  Kullanim:
rem    update-filters.bat                 -> rules.json'i it, uygulamayi yeniden baslat
rem    update-filters.bat dosyam.json     -> belirtilen dosyayi it
rem    update-filters.bat --reset         -> adb ile itilen kurallari sil (GitHub'a don)
rem ============================================================
set PKG=com.uykutube.app
set ACT=com.uykutube.app/.MainActivity

set ADB=adb
where adb >nul 2>nul || set ADB=C:\dev\android-sdk\platform-tools\adb.exe

set SRC=rules.json
if "%~1"=="" goto run
if /i "%~1"=="--reset" goto reset
set SRC=%~1

:run
if not exist "%SRC%" (
    echo [HATA] %SRC% bulunamadi.
    exit /b 1
)
echo [1/4] Kurallar dosyasi: %SRC%
"%ADB%" push "%SRC%" /data/local/tmp/firerules.json || goto err
echo [2/4] Uygulamaya kopyalaniyor...
"%ADB%" shell run-as %PKG% cp /data/local/tmp/firerules.json files/rules.local.json || goto err
"%ADB%" shell rm -f /data/local/tmp/firerules.json
echo [3/4] Uygulama yeniden baslatiliyor...
"%ADB%" shell am force-stop %PKG%
ping -n 2 127.0.0.1 >nul
echo [4/4] Baslatildi. Logda "KURALLAR: adb yerel (vN)" gormen gerekir.
"%ADB%" shell am start -n %ACT%
exit /b 0

:reset
echo Yerel kurallar siliniyor, GitHub kaynagina donuluyor...
"%ADB%" shell run-as %PKG% rm -f files/rules.local.json
"%ADB%" shell am force-stop %PKG%
ping -n 2 127.0.0.1 >nul
"%ADB%" shell am start -n %ACT%
exit /b 0

:err
echo [HATA] adb islemi basarisiz. Telefon bagli mi? (USB hata ayiklama acik mi?)
exit /b 1
