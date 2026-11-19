/*
 * agentpipe.exe: lets the Android app talk to Blizzard's Agent.
 *
 * The Agent (Agent.exe --internalclienttools) serves an HTTP API on
 * 127.0.0.1 and answers only programs it can find in the Windows TCP table.
 * Wine builds that table from its own sockets, so a request from the app's
 * Java code is refused (HTTP 401) while the same request from a program in
 * the container is accepted. This program runs in the container and passes
 * bytes between the app and the Agent unchanged.
 *
 * Usage: agentpipe.exe <listen port> <secret> [<idle seconds>]
 *
 * It listens on 127.0.0.1:<listen port>. A client first sends the line
 * "WHP <secret>\n", then plain HTTP. The pipe reads the Agent's current port
 * from C:\ProgramData\Battle.net\Agent\Agent.dat on each connection,
 * connects there and copies both directions until either side closes. A
 * connection with a wrong secret is closed. The pipe exits after
 * <idle seconds> (default 600) without a connection.
 */
#include <winsock2.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AGENT_DAT "C:\\ProgramData\\Battle.net\\Agent\\Agent.dat"
#define MAX_SECRET 128
#define BUFFER_SIZE 65536

static char g_secret[MAX_SECRET + 1];
static volatile LONG g_last_activity;

struct pair {
  SOCKET from;
  SOCKET to;
};

static int agent_port(void) {
  char text[16];
  FILE *f = fopen(AGENT_DAT, "rb");
  if (!f) return -1;
  size_t n = fread(text, 1, sizeof(text) - 1, f);
  fclose(f);
  text[n] = 0;
  int port = atoi(text);
  return port > 0 && port < 65536 ? port : -1;
}

static void touch(void) { InterlockedExchange(&g_last_activity, (LONG)GetTickCount()); }

static DWORD WINAPI copy_thread(LPVOID param) {
  struct pair *p = (struct pair *)param;
  char *buf = (char *)malloc(BUFFER_SIZE);
  if (buf) {
    for (;;) {
      int n = recv(p->from, buf, BUFFER_SIZE, 0);
      if (n <= 0) break;
      touch();
      int sent = 0;
      while (sent < n) {
        int m = send(p->to, buf + sent, n - sent, 0);
        if (m <= 0) goto done;
        sent += m;
      }
    }
  }
done:
  free(buf);
  shutdown(p->to, SD_SEND);
  return 0;
}

/* Reads "WHP <secret>\n" one byte at a time so no HTTP bytes are consumed. */
static int check_secret(SOCKET s) {
  char line[MAX_SECRET + 8];
  int len = 0;
  while (len < (int)sizeof(line) - 1) {
    char c;
    if (recv(s, &c, 1, 0) != 1) return 0;
    if (c == '\n') break;
    line[len++] = c;
  }
  line[len] = 0;
  if (len > 0 && line[len - 1] == '\r') line[--len] = 0;
  return strncmp(line, "WHP ", 4) == 0 && strcmp(line + 4, g_secret) == 0;
}

static DWORD WINAPI client_thread(LPVOID param) {
  SOCKET client = (SOCKET)(UINT_PTR)param;
  DWORD timeout = 10000;
  setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof(timeout));
  if (!check_secret(client)) {
    closesocket(client);
    return 0;
  }
  timeout = 0;
  setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout, sizeof(timeout));

  int port = agent_port();
  SOCKET agent = port > 0 ? socket(AF_INET, SOCK_STREAM, IPPROTO_TCP) : INVALID_SOCKET;
  struct sockaddr_in addr;
  ZeroMemory(&addr, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons((u_short)port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (agent == INVALID_SOCKET || connect(agent, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
    static const char unavailable[] =
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    send(client, unavailable, sizeof(unavailable) - 1, 0);
    if (agent != INVALID_SOCKET) closesocket(agent);
    closesocket(client);
    return 0;
  }

  struct pair up = {client, agent};
  struct pair down = {agent, client};
  HANDLE t = CreateThread(NULL, 0, copy_thread, &up, 0, NULL);
  copy_thread(&down);
  if (t) {
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
  }
  closesocket(agent);
  closesocket(client);
  touch();
  return 0;
}

static DWORD WINAPI idle_thread(LPVOID param) {
  DWORD idle_ms = (DWORD)(UINT_PTR)param;
  for (;;) {
    Sleep(5000);
    if ((DWORD)GetTickCount() - (DWORD)g_last_activity > idle_ms) ExitProcess(0);
  }
  return 0;
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nShowCmd) {
  (void)hInstance;
  (void)hPrevInstance;
  (void)lpCmdLine;
  (void)nShowCmd;
  if (__argc < 3) return 1;
  int listen_port = atoi(__argv[1]);
  if (listen_port <= 0 || listen_port >= 65536) return 1;
  if (strlen(__argv[2]) < 16 || strlen(__argv[2]) > MAX_SECRET) return 1;
  strcpy(g_secret, __argv[2]);
  int idle = __argc > 3 ? atoi(__argv[3]) : 600;
  if (idle < 10) idle = 10;

  WSADATA wsa;
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 2;
  SOCKET server = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (server == INVALID_SOCKET) return 3;
  struct sockaddr_in addr;
  ZeroMemory(&addr, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons((u_short)listen_port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (bind(server, (struct sockaddr *)&addr, sizeof(addr)) != 0) return 4;
  if (listen(server, 8) != 0) return 5;

  touch();
  HANDLE idle_handle = CreateThread(NULL, 0, idle_thread, (LPVOID)(UINT_PTR)(idle * 1000u), 0, NULL);
  if (idle_handle) CloseHandle(idle_handle);

  for (;;) {
    SOCKET client = accept(server, NULL, NULL);
    if (client == INVALID_SOCKET) continue;
    touch();
    HANDLE t = CreateThread(NULL, 0, client_thread, (LPVOID)(UINT_PTR)client, 0, NULL);
    if (t) CloseHandle(t);
    else closesocket(client);
  }
  return 0;
}
