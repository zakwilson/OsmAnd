@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

@rem ##########################################################################
@rem  UTF-8 locale guard (mirrors the POSIX `gradlew`; see its comment).
@rem  The JVM derives sun.jnu.encoding -- used to DECODE FILENAMES -- from the
@rem  Windows system ANSI code page (GetACP), NOT from -Dfile.encoding and NOT
@rem  from the console code page. So a non-ASCII test resource such as
@rem  resources\test-resources\search\ludwigstrasse.json (the real name uses sz)
@rem  fails to hash/copy in :OsmAnd-java:collectTestResources when the system
@rem  code page can't represent the character. `chcp 65001` below only fixes
@rem  CONSOLE text -- it does not change GetACP -- so we cannot fix this from a
@rem  script. We instead warn with the one real fix: the system-wide UTF-8
@rem  setting (which also makes GetACP return 65001).
@rem ##########################################################################
chcp 65001 >NUL 2>&1
set "_OSMAND_ACP="
for /f "tokens=3" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Nls\CodePage" /v ACP 2^>NUL ^| findstr /r "REG_SZ"') do set "_OSMAND_ACP=%%a"
if not "%_OSMAND_ACP%"=="65001" (
    echo.
    echo WARNING: Windows system code page is %_OSMAND_ACP%, not UTF-8 ^(65001^).
    echo          Gradle may fail on non-ASCII resource filenames with
    echo          "Failed to create MD5 hash ... as it does not exist".
    echo          Real fix ^(requires a reboot^): Settings ^> Time ^& Language ^>
    echo          Language ^& region ^> Administrative language settings ^>
    echo          Change system locale... ^> tick "Beta: Use Unicode UTF-8 for
    echo          worldwide language support" ^> OK ^> restart.
    echo.
)
set "_OSMAND_ACP="

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windows variants

if not "%OS%" == "Windows_NT" goto win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
