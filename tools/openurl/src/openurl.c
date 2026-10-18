/*
 * openurl.exe: the container's handler for http and https links.
 *
 * Wine's own browser (Wine Gecko) cannot render current sign-in pages, such
 * as the NetEase account page that Battle.net China opens. This program is
 * registered as the shell "open" command for http/https, so ShellExecute on a
 * link sends it to WinHandler on the Android side (UDP 127.0.0.1:7947), which
 * opens it in the phone's browser. Links that return to 127.0.0.1 (Battle.net's
 * login callback) still reach the container, because Wine shares the phone's
 * network.
 *
 * Packet: 0x75, uint16 little-endian byte length, UTF-8 URL.
 */
#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>
#include <stdint.h>
#include <string.h>

#define HOST_PORT 7947
#define PKT_OPEN_URL 0x75
#define MAX_URL_BYTES 7680

static int is_web_url(const WCHAR *s) {
  return s && (_wcsnicmp(s, L"https://", 8) == 0 || _wcsnicmp(s, L"http://", 7) == 0);
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nShowCmd) {
  (void)hInstance;
  (void)hPrevInstance;
  (void)lpCmdLine;
  (void)nShowCmd;

  int argc = 0;
  WCHAR **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!argv) return 1;

  const WCHAR *url = NULL;
  for (int i = 1; i < argc; i++) {
    if (is_web_url(argv[i])) {
      url = argv[i];
      break;
    }
  }
  if (!url) {
    LocalFree(argv);
    return 2;
  }

  uint8_t packet[3 + MAX_URL_BYTES];
  int n = WideCharToMultiByte(CP_UTF8, 0, url, -1, (char *)packet + 3, MAX_URL_BYTES, NULL, NULL);
  LocalFree(argv);
  if (n <= 1) return 3; /* empty, or longer than MAX_URL_BYTES */
  n -= 1;               /* drop the terminating NUL */

  packet[0] = PKT_OPEN_URL;
  packet[1] = (uint8_t)(n & 0xff);
  packet[2] = (uint8_t)((n >> 8) & 0xff);

  WSADATA wsa;
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 4;
  SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == INVALID_SOCKET) {
    WSACleanup();
    return 5;
  }
  struct sockaddr_in host;
  ZeroMemory(&host, sizeof(host));
  host.sin_family = AF_INET;
  host.sin_port = htons(HOST_PORT);
  host.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  int sent = sendto(s, (const char *)packet, 3 + n, 0, (const struct sockaddr *)&host, sizeof(host));
  closesocket(s);
  WSACleanup();
  return sent == 3 + n ? 0 : 6;
}
