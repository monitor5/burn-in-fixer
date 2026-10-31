package com.burnin.target.net

/**
 * 두 앱(측정/대상)이 공유하는 통신 규약.
 * 전송 계층: TCP, newline(\n) 구분 JSON 한 줄 = 메시지 하나.
 * 요청: {"cmd": "...", ...}  응답: {"ok": true|false, "cmd": "...", ...}
 * 보정맵 데이터는 base64 PNG로 "data" 필드에 실어 보내고 MD5 체크섬으로 무결성을 검증한다.
 */
object Protocol {
    const val PORT = 8899

    const val CMD_HELLO = "HELLO"
    const val CMD_SCREEN_INFO = "REQUEST_SCREEN_INFO"
    const val CMD_SHOW_PATTERN = "SHOW_PATTERN"
    const val CMD_APPLY_MAP = "APPLY_CORRECTION_MAP"
    const val CMD_ENABLE_CORRECTION = "ENABLE_CORRECTION"
    const val CMD_DISABLE_CORRECTION = "DISABLE_CORRECTION"
    const val CMD_ENABLE_OVERLAY = "ENABLE_OVERLAY"
    const val CMD_DISABLE_OVERLAY = "DISABLE_OVERLAY"
    const val CMD_APPLY_WHITE_BALANCE = "APPLY_WHITE_BALANCE"
    const val CMD_CLEAR_WHITE_BALANCE = "CLEAR_WHITE_BALANCE"
    const val CMD_APPLY_COLOR_PROFILE = "APPLY_COLOR_PROFILE"
    const val CMD_CLEAR_COLOR_PROFILE = "CLEAR_COLOR_PROFILE"
    const val CMD_SET_COLOR_PROFILE = "SET_COLOR_PROFILE"
    const val CMD_END_SESSION = "END_SESSION"

    const val ROLE_ADJUSTMENT = "adjustment"
    const val ROLE_REFERENCE = "reference"
}
