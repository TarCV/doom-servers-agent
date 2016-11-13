:Loop
IF [%1]==[] GOTO Continue
    @ECHO "%1"
SHIFT
GOTO Loop
:Continue
