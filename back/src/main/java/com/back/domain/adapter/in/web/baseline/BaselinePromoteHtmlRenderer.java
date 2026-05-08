package com.back.domain.adapter.in.web.baseline;

import com.back.domain.application.result.PromoteBaselineResult;

final class BaselinePromoteHtmlRenderer {

    private BaselinePromoteHtmlRenderer() {
    }

    static String render(PromoteBaselineResult result) {
        return switch (result.status()) {
            case SUCCESS -> page(
                    "기준 갱신 완료",
                    "이 시점이 새 기준으로 설정되었습니다. 다음 비교부터 이 시점을 기준으로 변화를 감지합니다.",
                    "#0f7a4f"
            );
            case DRY_RUN -> page(
                    "링크가 유효합니다",
                    "기준 갱신 링크가 유효합니다. 실제 갱신을 적용하려면 한 번 더 클릭해 주세요.",
                    "#7a5a24"
            );
            case ALREADY_USED -> page(
                    "이미 사용된 링크",
                    "이 링크는 이미 사용되었습니다. 새 알림이 도착하면 그때 다시 시도해 주세요.",
                    "#7a5a24"
            );
            case EXPIRED -> page(
                    "만료된 링크",
                    "이 링크는 만료되었습니다. 새 알림이 도착하면 그때 다시 시도해 주세요.",
                    "#7a5a24"
            );
            case NOT_FOUND -> page(
                    "유효하지 않은 링크",
                    "올바르지 않은 링크입니다.",
                    "#b91c1c"
            );
            case MCP_FAILED -> page(
                    "기준 갱신 실패",
                    "기준 갱신에 실패했습니다. 잠시 후 다시 시도해 주세요.",
                    "#b91c1c"
            );
        };
    }

    private static String page(String title, String message, String accentColor) {
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>%s · 지켜봐줄게</title>
                </head>
                <body style="margin:0;background:#f7f2e8;color:#1c1917;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <div style="max-width:520px;margin:60px auto;padding:20px 14px;">
                    <div style="background:#fffdf7;border:1px solid #e6d9c3;border-radius:20px;padding:28px;box-shadow:0 12px 32px rgba(61,46,26,0.08);">
                      <span style="display:inline-block;background:%s;color:#ffffff;border-radius:999px;padding:7px 12px;font-size:12px;line-height:1;font-weight:800;">지켜봐줄게</span>
                      <h1 style="margin:14px 0 12px;font-size:22px;line-height:1.35;color:#211a12;font-weight:900;letter-spacing:-0.04em;">%s</h1>
                      <p style="margin:0;color:#4d4033;font-size:15px;line-height:1.6;font-weight:600;">%s</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(escape(title), accentColor, escape(title), escape(message));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
