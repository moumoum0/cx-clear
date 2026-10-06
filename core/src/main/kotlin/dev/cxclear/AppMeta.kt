package dev.cxclear

object AppMeta {
    const val NAME = "CX Clear"
    // 应用版本号；只改 build.gradle.kts 的 version
    const val VERSION = AppVersion.VALUE
    const val DEVELOPER = "moumoum"

    // 空字符串 = 行仍展示，点击无动作。填入后用系统浏览器打开。
    const val BILIBILI_URL = ""
    const val GITHUB_URL = "https://github.com/moumoum0/cx-clear"
    const val QQ_GROUP_URL = ""

    // 来源调研 issue：调研条「去填写」跳这里，用户按「来源 + 功能」回复。
    const val SURVEY_ISSUE_URL = "https://github.com/moumoum0/cx-clear/issues/6"
}
