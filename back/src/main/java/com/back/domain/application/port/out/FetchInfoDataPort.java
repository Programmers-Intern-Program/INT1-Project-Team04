package com.back.domain.application.port.out;

import java.util.Optional;

public interface FetchInfoDataPort {

    Optional<InfoDataResult> fetch(String domainName, String query);

    record InfoDataResult(String summary, String rawContent) {}
}
