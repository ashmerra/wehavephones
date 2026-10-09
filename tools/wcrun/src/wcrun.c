/*
 * wcrun.exe <command line>
 *
 * Runs "cmd.exe /s /c "<command line>"" without a console window and exits
 * with cmd's exit code. WeHavePhones starts its .bat launchers through this so
 * Wine does not open a console window (conhost) before Battle.net or WoW.
 * Built as a GUI program (-mwindows) so wcrun itself has no console either.
 */
#include <windows.h>
#include <wchar.h>

/* A 32-bit cmd redirects System32 writes into SysWOW64. The launch batches
 * install ARM64 graphics DLLs, so run them with the native cmd instead. */
static BOOL get_command_processor(WCHAR *path, UINT capacity) {
  BOOL wow64 = FALSE;
  if (IsWow64Process(GetCurrentProcess(), &wow64) && wow64) {
    UINT n = GetWindowsDirectoryW(path, capacity);
    const WCHAR suffix[] = L"\\Sysnative\\cmd.exe";
    if (n == 0 || n + ARRAYSIZE(suffix) > capacity) return FALSE;
    wcscat(path, suffix);
    /* Failing here is safer than silently running a redirected batch. */
    return GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES;
  }

  UINT n = GetSystemDirectoryW(path, capacity);
  const WCHAR suffix[] = L"\\cmd.exe";
  if (n == 0 || n + ARRAYSIZE(suffix) > capacity) return FALSE;
  wcscat(path, suffix);
  return TRUE;
}

/* Command line after argv[0], following the CommandLineToArgvW quoting rules. */
static const WCHAR *skip_program_name(const WCHAR *p) {
  if (*p == L'"') {
    ++p;
    while (*p && *p != L'"') ++p;
    if (*p) ++p;
  } else {
    while (*p && *p != L' ' && *p != L'\t') ++p;
  }
  while (*p == L' ' || *p == L'\t') ++p;
  return p;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR unused, int show) {
  (void)instance;
  (void)previous;
  (void)unused;
  (void)show;

  const WCHAR *tail = skip_program_name(GetCommandLineW());
  if (!*tail) return 2;

  WCHAR cmd_exe[MAX_PATH];
  if (!get_command_processor(cmd_exe, ARRAYSIZE(cmd_exe))) return 3;

  size_t size = wcslen(cmd_exe) + wcslen(tail) + 16;
  WCHAR *command_line = (WCHAR *)HeapAlloc(GetProcessHeap(), 0, size * sizeof(WCHAR));
  if (!command_line) return 4;
  /* /s: cmd strips exactly the outer quotes, keeping any quotes in tail. */
  _snwprintf(command_line, size, L"\"%ls\" /s /c \"%ls\"", cmd_exe, tail);
  command_line[size - 1] = 0;

  STARTUPINFOW startup;
  PROCESS_INFORMATION process;
  ZeroMemory(&startup, sizeof(startup));
  ZeroMemory(&process, sizeof(process));
  startup.cb = sizeof(startup);
  startup.dwFlags = STARTF_USESHOWWINDOW;
  startup.wShowWindow = SW_HIDE;

  if (!CreateProcessW(cmd_exe, command_line, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &startup, &process)) {
    return 5;
  }
  CloseHandle(process.hThread);
  WaitForSingleObject(process.hProcess, INFINITE);
  DWORD code = 0;
  GetExitCodeProcess(process.hProcess, &code);
  CloseHandle(process.hProcess);
  return (int)code;
}
