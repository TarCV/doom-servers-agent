:Loop
IF [%1]==[] GOTO Continue
    @ECHO "%1"
SHIFT
GOTO Loop
:Continue
ECHO DoomServerReady
SET /P TMPVAR=
ECHO %TMPVAR%
