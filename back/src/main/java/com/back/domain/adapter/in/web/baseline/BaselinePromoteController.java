package com.back.domain.adapter.in.web.baseline;

import com.back.domain.application.port.in.PromoteBaselineUseCase;
import com.back.domain.application.result.PromoteBaselineResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
@RequiredArgsConstructor
public class BaselinePromoteController {

    private final PromoteBaselineUseCase promoteBaselineUseCase;

    @GetMapping(value = "/baseline-promote/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> promote(
            @PathVariable String token,
            @RequestHeader(value = "Purpose", required = false) String purpose
    ) {
        // 메일/메신저 클라이언트의 prefetch 가 토큰을 소비하지 않도록 헤더 휴리스틱.
        boolean dryRun = "prefetch".equalsIgnoreCase(purpose);
        PromoteBaselineResult result = promoteBaselineUseCase.promote(token, dryRun);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
                .body(BaselinePromoteHtmlRenderer.render(result));
    }
}
