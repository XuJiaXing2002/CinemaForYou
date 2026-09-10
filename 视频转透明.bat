@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title 视频转透明（真 alpha 通道）

rem =================== 用法 ===================
rem  用法1：把视频文件拖到本文件图标上（支持多选）
rem  用法2：双击本文件，再把视频路径拖进窗口按回车
rem  用法3：命令行  视频转透明.bat "视频.mp4" [背景色]
rem         背景色可不填：不填时会停下来让你交互选择/输入要抠的颜色；
rem         要跳过交互直接转，就写 0xRRGGBB（绿幕 0x00FF00 / 黑底 0x000000 / 白底 0xFFFFFF）
rem  输出：在"当前工作目录"下生成  原文件名_透明.webm
rem ============================================

rem -------- 可调参数 --------
rem 抠像容差：背景没抠干净 -> 调大(0.2~0.4)；主体被吃掉 -> 调小(0.05~0.1)
rem （双击/拖入运行时也会在选色后让你再调一次，命令行带 0x 颜色时用此默认值）
set "SIM=0.20"
rem 画质：数字越小越清晰、文件越大（18=高画质，28=小体积）
set "CRF=23"
rem 验证时检查的帧数（确认生成的文件确实带透明通道）
set "VF=30"
rem --------------------------

where ffmpeg >nul 2>nul
if errorlevel 1 (
    echo.
    echo   [X] 没找到 ffmpeg。请先把它所在的 bin 目录加入系统 PATH。
    pause
    exit /b 1
)

set "BG="

if "%~1"=="" goto interactive
goto hasargs

:interactive
echo.
set "IN="
set /p "IN=把视频文件拖到这一行，然后回车: "
if not defined IN (
    echo   没有输入文件。
    pause
    exit /b 1
)
call :convert "%IN%"
goto done

:hasargs
rem 先扫一遍参数挑出 0xRRGGBB 背景色，再逐个转换视频（顺序无关）
for %%A in (%*) do call :pickcolor "%%~A"
for %%A in (%*) do call :dispatch "%%~A"
goto done

:pickcolor
set "X=%~1"
if "%X:~0,2%"=="0x" set "BG=%X%"
exit /b 0

:dispatch
set "X=%~1"
if not "%X:~0,2%"=="0x" call :convert "%X%"
exit /b 0

:done
echo.
echo 完成。把生成的 _透明.webm 拖进模组指定的视频本地目录即可（模组会自动识别透明通道）。
pause
exit /b 0


rem ==================== 转换单个文件 ====================
:convert
set "FILE=%~1"
if not exist "%FILE%" (
    echo.
    echo   [X] 找不到文件: %FILE%
    exit /b 1
)

rem 输出到"当前工作目录"（%CD%），文件名 = 原名_透明.webm
for %%F in ("%FILE%") do set "NAME=%%~nF"
set "OUT=%CD%\%NAME%_透明.webm"

echo.
echo   === 转换: %FILE%
echo       输出: %OUT%

rem ---------- 步骤1：探测输入视频是否已带 alpha 通道 ----------
set "FMT="
for /f "delims=" %%P in ('ffprobe -v error -select_streams v:0 -show_entries stream^=pix_fmt -of csv^=p^=0 "%FILE%" 2^>nul') do set "FMT=%%P"
set "HASALPHA=0"
if defined FMT (
    echo %FMT% | findstr /i "yuva argb rgba bgra pal8" >nul && set "HASALPHA=1"
)
echo       输入像素格式: %FMT%  是否已带alpha: %HASALPHA%

rem ---------- 步骤2：决定背景色（仅当输入无 alpha 时需要抠像） ----------
set "COLOR=%BG%"
if "%HASALPHA%"=="1" goto havealpha

rem 命令行已指定颜色 → 直接用，不交互
if not "%COLOR%"=="" goto havecolor

rem 自动取左上角 8x8 颜色作为参考（用户可改）
set "COLOR_AUTO=0x00FF00"
set "RAW=%TEMP%\cfy_bg_%RANDOM%.raw"
set "HEX=%TEMP%\cfy_bg_%RANDOM%.hex"
set "HLINE="
ffmpeg -v error -y -i "%FILE%" -vf "select=eq(n\,0),crop=8:8:0:0,scale=1:1" -frames:v 1 -f rawvideo -pix_fmt rgb24 "%RAW%" 2>nul
if not exist "%RAW%" goto auto_done
certutil -encodehex -f "%RAW%" "%HEX%" 4 >nul 2>&1
set /p "HLINE=" < "%HEX%"
:auto_done
del "%RAW%" "%HEX%" >nul 2>&1
if defined HLINE set "COLOR_AUTO=0x!HLINE: =!"
if not "!COLOR_AUTO:~0,2!"=="0x" set "COLOR_AUTO=0x00FF00"
if "!COLOR_AUTO:~7,1%"=="" set "COLOR_AUTO=0x00FF00"

:pickcolor
echo.
echo   请选择要抠掉的背景色：
echo     1 = 自动识别 (左上角8x8): !COLOR_AUTO!
echo     2 = 绿幕 0x00FF00
echo     3 = 蓝幕 0x0000FF
echo     4 = 黑底 0x000000
echo     5 = 白底 0xFFFFFF
echo     p = 导出首帧为 PNG (用画图软件取色后重来)
echo     其它 = 直接输入 0xRRGGBB (如 0x80C0FF)
set "CHOICE="
set /p "CHOICE=选择 [1]: "
if not defined CHOICE (
    set "COLOR=!COLOR_AUTO!"
    goto pickcolor_done
)
if "%CHOICE%"=="1" (
    set "COLOR=!COLOR_AUTO!"
    goto pickcolor_done
)
if "%CHOICE%"=="2" ( set "COLOR=0x00FF00" & goto pickcolor_done )
if "%CHOICE%"=="3" ( set "COLOR=0x0000FF" & goto pickcolor_done )
if "%CHOICE%"=="4" ( set "COLOR=0x000000" & goto pickcolor_done )
if "%CHOICE%"=="5" ( set "COLOR=0xFFFFFF" & goto pickcolor_done )
if /i "%CHOICE%"=="p" (
    set "PNG=%CD%\%NAME%_首帧.png"
    ffmpeg -v error -y -i "%FILE%" -vf "select=eq(n\,0)" -frames:v 1 "!PNG!" 2>nul
    if exist "!PNG!" (
        echo   已导出: !PNG!
        echo   用画图/Photoshop 打开，取色器读 RGB，再回来输入 0xRRGGBB
    ) else (
        echo   [!] 导出失败
    )
    goto pickcolor
)
if /i "%CHOICE:~0,2%"=="0x" (
    set "COLOR=%CHOICE%"
    goto pickcolor_done
)
echo   无效输入，请重选
goto pickcolor

:pickcolor_done
echo.
echo   当前容差: %SIM%  (没抠干净调大 0.2-0.4；主体被吃掉调小 0.05-0.1)
set "NEWSIM="
set /p "NEWSIM=输入新容差 [回车=保持默认]: "
if defined NEWSIM set "SIM=!NEWSIM!"

:havecolor
rem 绿幕/蓝幕用 chromakey（只比色度，不怕亮度不均）；其它纯色用 colorkey
set /a "RD=0x%COLOR:~2,2%, GN=0x%COLOR:~4,2%, BL=0x%COLOR:~6,2%"
set /a "DG=GN-RD, DB=GN-BL, DBR=BL-RD, DBG=BL-GN"
set "KEY=colorkey"
if %DG% GTR 40 if %DB% GTR 40 set "KEY=chromakey"
if %DBR% GTR 40 if %DBG% GTR 40 set "KEY=chromakey"
echo       背景色 %COLOR%  抠像滤镜 %KEY%  容差 %SIM%

rem ---------- 步骤3：执行转换（无 alpha 输入：抠像 + 编码） ----------
rem -nostdin：防止 ffmpeg 抢占 stdin，保证交互式 set /p 不被干扰
rem -g 60：每秒一个关键帧（默认240太大），让 seek/循环重开更快
rem -deadline good -rc_lookahead 0：简化码流，减少解码器初始化负担
ffmpeg -y -hide_banner -nostdin -i "%FILE%" ^
    -vf "%KEY%=%COLOR%:%SIM%:0.05,format=yuva420p" ^
    -c:v libvpx-vp9 -pix_fmt yuva420p -auto-alt-ref 0 -g 60 -crf %CRF% -b:v 0 ^
    -deadline good -rc_lookahead 0 -row-mt 1 -cpu-used 3 ^
    -c:a libopus "%OUT%"
if errorlevel 1 (
    echo.
    echo   [X] 转换失败
    exit /b 1
)
goto verify

:havealpha
rem 输入已带 alpha：不做抠像，仅转码为 WebM/yuva420p（保留原有透明区域）
echo       输入已带 alpha 通道，跳过抠像，直接转码保留透明
ffmpeg -y -hide_banner -nostdin -i "%FILE%" ^
    -vf "format=yuva420p" ^
    -c:v libvpx-vp9 -pix_fmt yuva420p -auto-alt-ref 0 -g 60 -crf %CRF% -b:v 0 ^
    -deadline good -rc_lookahead 0 -row-mt 1 -cpu-used 3 ^
    -c:a libopus "%OUT%"
if errorlevel 1 (
    echo.
    echo   [X] 转换失败
    exit /b 1
)

:verify
rem ---------- 步骤4：复检 alpha 平面上是否真有透明像素 ----------
echo.
echo   [复检] 验证生成的文件是否真带透明通道...
ffmpeg -v info -c:v libvpx-vp9 -i "%OUT%" ^
    -vf "format=yuva420p,alphaextract,blackframe=amount=0:threshold=1" ^
    -frames:v %VF% -f null - 2>&1 | findstr /r /c:"pblack:[1-9]" >nul
if errorlevel 1 (
    echo   [!] 文件生成了，但里面没有透明像素 —— 背景色可能没选对，可在命令后加背景色重试
) else (
    echo   [OK] 转换成功，已确认带真透明通道
)
exit /b 0
