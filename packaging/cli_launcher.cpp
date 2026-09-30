#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <string>

static std::wstring parent_dir(const std::wstring& path) {
    const auto pos = path.find_last_of(L"\\/");
    return pos == std::wstring::npos ? L"." : path.substr(0, pos);
}

static bool is_file(const std::wstring& path) {
    const DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

static bool is_dir(const std::wstring& path) {
    const DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) != 0;
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

// 捆绑 JRE 优先，否则 JAVA_HOME；不遍历 PATH
static std::wstring find_java(const std::wstring& home) {
    const std::wstring bundled = home + L"\\runtime\\bin\\java.exe";
    if (is_file(bundled)) {
        return bundled;
    }

    wchar_t java_home[32768];
    const DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", java_home, 32768);
    if (n > 0 && n < 32768) {
        std::wstring jdk(java_home, n);
        trim_slash(jdk);
        const std::wstring p = jdk + L"\\bin\\java.exe";
        if (is_file(p)) {
            return p;
        }
    }

    return L"";
}

// GUI 包的启动器在 cli/，jar 和 runtime 在上一级；独立包就在当前目录
static std::wstring app_home() {
    const std::wstring dir = module_dir();
    if (is_dir(dir + L"\\app") || is_dir(dir + L"\\lib") || is_dir(dir + L"\\runtime")) {
        return dir;
    }
    const std::wstring parent = parent_dir(dir);
    if (is_dir(parent + L"\\app") || is_dir(parent + L"\\lib") || is_dir(parent + L"\\runtime")) {
        return parent;
    }
    return dir;
}

// 安装包里 jar 在 app/；installDist 里 jar 在 lib/
static std::wstring classpath_dir(const std::wstring& home) {
    const std::wstring app = home + L"\\app";
    if (is_dir(app)) {
        return app;
    }
    const std::wstring lib = home + L"\\lib";
    if (is_dir(lib)) {
        return lib;
    }
    return app;
}

static std::wstring quote(const std::wstring& value) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (const wchar_t ch : value) {
        if (ch == L'\\') {
            ++slashes;
        } else if (ch == L'"') {
            result.append(slashes * 2 + 1, L'\\');
            result += ch;
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            result += ch;
            slashes = 0;
        }
    }
    result.append(slashes * 2, L'\\');
    return result + L'"';
}

static void fail(const wchar_t* message) {
    HANDLE err = GetStdHandle(STD_ERROR_HANDLE);
    if (err != nullptr && err != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        DWORD mode = 0;
        if (GetConsoleMode(err, &mode)) {
            WriteConsoleW(err, message, static_cast<DWORD>(wcslen(message)), &written, nullptr);
            WriteConsoleW(err, L"\r\n", 2, &written, nullptr);
        } else {
            const std::wstring line = std::wstring(message) + L"\r\n";
            const int bytes = WideCharToMultiByte(CP_UTF8, 0, line.c_str(), -1, nullptr, 0, nullptr, nullptr);
            std::string utf8(bytes, '\0');
            WideCharToMultiByte(CP_UTF8, 0, line.c_str(), -1, utf8.data(), bytes, nullptr, nullptr);
            WriteFile(err, utf8.data(), static_cast<DWORD>(bytes - 1), &written, nullptr);
        }
    }
}

int wmain(int argc, wchar_t** argv) {
    const std::wstring home = app_home();
    const std::wstring java = find_java(home);
    if (java.empty()) {
        fail(L"找不到 Java 21。请安装 JDK 21 并设置 JAVA_HOME，或使用带 Java 的安装包。");
        return 1;
    }

    const std::wstring cp = classpath_dir(home);
    std::wstring cmd = quote(java)
        + L" -Dfile.encoding=UTF-8"
        + L" -cp " + quote(cp + L"\\*")
        + L" dev.cxclear.cli.MainKt";

    for (int i = 1; i < argc; ++i) {
        cmd += L" " + quote(argv[i]);
    }

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    std::wstring mutable_cmd = cmd;
    if (!CreateProcessW(
            java.c_str(),
            mutable_cmd.data(),
            nullptr,
            nullptr,
            TRUE,
            0,
            nullptr,
            nullptr,
            &si,
            &pi)) {
        fail(L"启动 Java 失败。");
        return 1;
    }
    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    return static_cast<int>(code);
}
