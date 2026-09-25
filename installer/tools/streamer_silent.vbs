Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
strDir = fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.CurrentDirectory = strDir

If fso.FileExists(strDir & "\kdeconnect-streamer.exe") Then
    WshShell.Run """" & strDir & "\kdeconnect-streamer.exe""", 0, False
Else
    pyExe = "python.exe"
    If fso.FileExists("C:\Python3\pythonw.exe") Then
        pyExe = "C:\Python3\pythonw.exe"
    ElseIf fso.FileExists(strDir & "\..\..\pythonw.exe") Then
        pyExe = strDir & "\..\..\pythonw.exe"
    End If
    WshShell.Run """" & pyExe & """ screen_streamer.py", 0, False
End If

