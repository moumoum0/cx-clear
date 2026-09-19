#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <string>

static std::wstring parent_dir(const std::wstring& path) {
    const auto pos = path.find_last_of(L"\\/");
    return pos == std::wstring::npos ? L"." : path.substr(0, pos);
}

static bool is_file(const std::wstring& path) {
    const DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

static void trim_slash(std::wstring& path) {
    while (!path.empty() && (path.back() == L'\\' || path.back() == L'/')) {
        path.pop_back();
    }
}

static std::wstring module_dir() {
    wchar_t buf[32768];
    const DWORD n = GetModuleFileNameW(nullptr, buf, 32768);
    if (n == 0 || n >= 32768) {
        return L".";
    }
    return parent_dir(std::wstring(buf, n));
}

// 捆绑JRE优先，否则 JAVA_HOME；不再遍历 PATH（性能考虑）
static std::wstring find_javaw(const std::wstring& app_home) {
    const std::wstring bundled = app_home + L"\\runtime\\bin\\javaw.exe";
    if (is_file(bundled)) {
        return bundled;
    }

    wchar_t java_home[32768];
    const DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", java_home, 32768);
    if (n > 0 && n < 32768) {
        std::wstring home(java_home, n);
        trim_slash(home);
        const std::wstring p = home + L"\\bin\\javaw.exe";
        if (is_file(p)) {
            return p;
        }
    }

    return L"";
}

static std::wstring quote(const std::wstring& s) {
    return L"\"" + s + L"\"";
}

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    const std::wstring home = module_dir();
    const std::wstring app_dir = home + L"\\app";
    const std::wstring javaw = find_javaw(home);
    if (javaw.empty()) {
        MessageBoxW(
            nullptr,
            L"找不到 Java 21。请安装 JDK 21 并设置 JAVA_HOME，或使用带 Java 的安装包。",
            L"CX Clear",
            MB_OK | MB_ICONERROR);
        return 1;
    }

    std::wstring cmd = quote(javaw)
        + L" -Dfile.encoding=UTF-8"
        + L" -Dsun.java2d.uiScale.enabled=true"
        + L" -Dcompose.application.configure.swing.globals=true"
        + L" -Dskiko.library.path=" + quote(app_dir)
        + L" -Djava.library.path=" + quote(app_dir)
        + L" -cp " + quote(app_dir + L"\\*")
        + L" dev.cxclear.MainKt";

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    std::wstring mutable_cmd = cmd;
    if (!CreateProcessW(
            javaw.c_str(),
            mutable_cmd.data(),
            nullptr,
            nullptr,
            FALSE,
            0,
            nullptr,
            home.c_str(),
            &si,
            &pi)) {
        MessageBoxW(nullptr, L"启动 Java 失败。", L"CX Clear", MB_OK | MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
